package io.leblanc.woolstock.scan

import androidx.lifecycle.ViewModel
import io.leblanc.woolstock.parser.FrameDump
import io.leblanc.woolstock.parser.Gtin
import io.leblanc.woolstock.parser.LabelDump
import io.leblanc.woolstock.parser.LabelParseResult
import io.leblanc.woolstock.parser.LabelParser
import io.leblanc.woolstock.parser.OcrLine
import io.leblanc.woolstock.parser.ParsedFields
import io.leblanc.woolstock.parser.ScanAccumulator
import io.leblanc.woolstock.parser.ScanSnapshot
import io.leblanc.woolstock.parser.toDump
import io.leblanc.woolstock.platform.ScanFrame
import io.leblanc.woolstock.platform.deviceName
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.TimeSource

data class DebugFrame(val lines: List<OcrLine>, val result: LabelParseResult)

/** Étiquette figée par une capture, à confirmer. */
data class CapturedLabel(
    val ean: String?,
    val dyeLot: String?,
    val dyeLotRaw: String?,
    val brand: String?,
    val colorCode: String?,
    val colorName: String?,
)

data class ScanUiState(
    /** Dernier code-barre lu, affiché dès la première détection. */
    val lastEan: String? = null,
    val snapshot: ScanSnapshot = ScanSnapshot(),
    val autoCaptureProgress: Float = 0f,
    val debugEnabled: Boolean = false,
    val debugFrame: DebugFrame? = null,
    val torchOn: Boolean = false,
)

/**
 * Vote sur les images successives de la caméra jusqu'à une étiquette stable. Les images
 * arrivent déjà analysées (code-barre et OCR) par l'aperçu de la plateforme, depuis son fil
 * d'analyse : l'état partagé est protégé par [lock].
 */
class ScanViewModel(private val parser: LabelParser) : ViewModel() {
    private val lock = SynchronizedObject()
    private val started = TimeSource.Monotonic.markNow()
    private val accumulator = ScanAccumulator()
    private val recentFrames = ArrayDeque<FrameDump>()
    private var captureSent = false

    /**
     * Dernière étiquette capturée (EAN, bain) : au retour de la confirmation, elle est souvent
     * encore sous l'objectif ; on ne la recapture automatiquement qu'après l'avoir perdue de vue.
     */
    private var lastCapturedKey: Pair<String, String>? = null
    private var framesWithoutBarcode = 0

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _captures = Channel<CapturedLabel>(Channel.CONFLATED)
    val captures: Flow<CapturedLabel> = _captures.receiveAsFlow()

    private class FrameOutcome(val snapshot: ScanSnapshot, val parsed: LabelParseResult, val progress: Float, val autoCapture: CapturedLabel?)

    /** Une image analysée par la caméra : code-barre et lignes OCR dans le repère de l'aperçu. */
    fun onFrame(frame: ScanFrame) {
        val ean = frame.barcodes.firstNotNullOfOrNull { Gtin.normalize(it) }
        val lines = frame.lines
        val debug = _state.value.debugEnabled

        val outcome = synchronized(lock) {
            val parsed = parser.parse(lines, ean ?: accumulator.snapshot().ean?.value)
            val snapshot = accumulator.add(ean, parsed, started.elapsedNow().inWholeMilliseconds)
            if (lines.isNotEmpty()) {
                recentFrames.addLast(FrameDump(lines.map { it.toDump() }, ean, frame.text))
                while (recentFrames.size > EXPORT_FRAMES) recentFrames.removeFirst()
            }
            framesWithoutBarcode = if (ean == null) framesWithoutBarcode + 1 else 0
            if (framesWithoutBarcode >= EXPORT_FRAMES) lastCapturedKey = null
            val key = snapshot.captureKey()
            val autoCapture = if (!debug && !captureSent && key != lastCapturedKey && accumulator.isReadyForAutoCapture(snapshot)) {
                captureSent = true
                lastCapturedKey = key
                snapshot.toCapturedLabel()
            } else {
                null
            }
            FrameOutcome(snapshot, parsed, accumulator.autoCaptureProgress(snapshot), autoCapture)
        }

        _state.update {
            it.copy(
                lastEan = ean ?: it.lastEan,
                snapshot = outcome.snapshot,
                autoCaptureProgress = outcome.progress,
                debugFrame = if (it.debugEnabled) DebugFrame(lines, outcome.parsed) else null,
            )
        }
        outcome.autoCapture?.let { _captures.trySend(it) }
    }

    /** Fige le meilleur résultat courant, même sous les seuils de vote : l'utilisateur confirmera. */
    fun capture() {
        val label = synchronized(lock) {
            val snapshot = accumulator.snapshot()
            captureSent = true
            lastCapturedKey = snapshot.captureKey() ?: lastCapturedKey
            snapshot.toCapturedLabel()
        }
        _captures.trySend(label)
    }

    /** À chaque retour sur l'écran caméra : nouvelle étiquette, caméra (et lampe) redémarrée. */
    fun resetScan() {
        synchronized(lock) {
            accumulator.reset()
            recentFrames.clear()
            captureSent = false
            framesWithoutBarcode = 0
        }
        _state.update { ScanUiState(debugEnabled = it.debugEnabled) }
    }

    fun setDebug(enabled: Boolean) = _state.update { it.copy(debugEnabled = enabled, debugFrame = null) }

    fun setTorch(on: Boolean) = _state.update { it.copy(torchOn = on) }

    /** Dernières frames OCR brutes + ce que propose l'app, au format des fixtures de test. */
    fun exportDump(): LabelDump? = synchronized(lock) {
        if (recentFrames.isEmpty()) return null
        val snapshot = accumulator.snapshot()
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        LabelDump(
            id = "real-" + now.format(EXPORT_STAMP),
            source = LabelDump.SOURCE_REAL,
            ean = snapshot.ean?.value,
            frames = recentFrames.toList(),
            parsed = ParsedFields(
                dyeLot = snapshot.dyeLot?.value,
                dyeLotRaw = snapshot.dyeLot?.rawValue,
                brand = snapshot.brand?.value,
                colorCode = snapshot.colorCode?.value,
                colorName = snapshot.colorName?.value,
            ),
            exportedAt = now.toString(),
            device = deviceName(),
        )
    }

    private fun ScanSnapshot.captureKey(): Pair<String, String>? {
        val eanValue = ean?.value ?: return null
        val lotValue = dyeLot?.value ?: return null
        return eanValue to lotValue
    }

    private fun ScanSnapshot.toCapturedLabel() = CapturedLabel(
        ean = ean?.value,
        dyeLot = dyeLot?.value,
        dyeLotRaw = dyeLot?.rawValue,
        brand = brand?.value,
        colorCode = colorCode?.value,
        colorName = colorName?.value,
    )

    private companion object {
        const val EXPORT_FRAMES = 15

        /** 20261025-213012 : l'heure locale de l'export, dans le nom du relevé. */
        val EXPORT_STAMP = LocalDateTime.Format {
            year()
            monthNumber()
            day()
            char('-')
            hour()
            minute()
            second()
        }
    }
}
