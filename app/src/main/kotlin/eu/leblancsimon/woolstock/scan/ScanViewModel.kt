package eu.leblancsimon.woolstock.scan

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import eu.leblancsimon.woolstock.WoolStockApp
import eu.leblancsimon.woolstock.parser.FrameDump
import eu.leblancsimon.woolstock.parser.Gtin
import eu.leblancsimon.woolstock.parser.LabelDump
import eu.leblancsimon.woolstock.parser.LabelParseResult
import eu.leblancsimon.woolstock.parser.LabelParser
import eu.leblancsimon.woolstock.parser.OcrLine
import eu.leblancsimon.woolstock.parser.ParsedFields
import eu.leblancsimon.woolstock.parser.ScanAccumulator
import eu.leblancsimon.woolstock.parser.ScanSnapshot
import eu.leblancsimon.woolstock.parser.toDump
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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

class ScanViewModel(private val parser: LabelParser) : ViewModel() {
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    /** ML Kit peut encore poster une annulation après la fermeture de l'écran : on l'ignore. */
    val analysisExecutor = Executor { command ->
        try {
            executorService.execute(command)
        } catch (_: RejectedExecutionException) {
        }
    }

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .build(),
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Code-barre et OCR sur la même image ; coordonnées ramenées dans le repère de la PreviewView. */
    val analyzer = MlKitAnalyzer(
        listOf(barcodeScanner, textRecognizer),
        ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
        analysisExecutor,
    ) { result -> onFrame(result) }

    private val lock = Any()
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

    private fun onFrame(result: MlKitAnalyzer.Result) {
        result.getThrowable(barcodeScanner)?.let { Log.w(TAG, "Barcode scanning failed", it) }
        result.getThrowable(textRecognizer)?.let { Log.w(TAG, "Text recognition failed", it) }
        val ean = result.getValue(barcodeScanner).orEmpty().firstNotNullOfOrNull { Gtin.normalize(it.rawValue) }
        val text = result.getValue(textRecognizer)
        val lines = text?.toOcrLines().orEmpty()
        val debug = _state.value.debugEnabled

        val outcome = synchronized(lock) {
            val parsed = parser.parse(lines, ean ?: accumulator.snapshot().ean?.value)
            val snapshot = accumulator.add(ean, parsed, SystemClock.elapsedRealtime())
            if (lines.isNotEmpty()) {
                recentFrames.addLast(FrameDump(lines.map { it.toDump() }, ean, text?.text))
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
        val now = LocalDateTime.now()
        LabelDump(
            id = "real-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")),
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
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    override fun onCleared() {
        barcodeScanner.close()
        textRecognizer.close()
        executorService.shutdown()
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

    companion object {
        private const val TAG = "ScanViewModel"
        private const val EXPORT_FRAMES = 15

        val Factory = viewModelFactory {
            initializer { ScanViewModel((this[APPLICATION_KEY] as WoolStockApp).container.labelParser) }
        }
    }
}
