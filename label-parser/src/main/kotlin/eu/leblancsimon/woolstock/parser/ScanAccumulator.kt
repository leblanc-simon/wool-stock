package eu.leblancsimon.woolstock.parser

/** Résultat du vote pour un champ sur les dernières frames. */
data class Vote(
    val value: String,
    val rawValue: String,
    val votes: Int,
    /** Frames de la fenêtre qui proposaient une valeur pour ce champ. */
    val considered: Int,
    val windowFrames: Int,
    val confidence: Float,
    /** Vrai si la fréquence dépasse les seuils : la valeur est considérée comme stable. */
    val accepted: Boolean,
)

/**
 * Histogramme des candidats sur les N dernières frames. Un tampon d'encre mal lu une frame sur deux
 * finit quand même par ressortir : c'est la valeur la plus fréquente qui l'emporte.
 */
class CandidateVoter(
    private val windowSize: Int = 15,
    private val minVotes: Int = 3,
    private val minShare: Float = 0.5f,
) {
    private val window = ArrayDeque<Candidate?>()

    fun add(candidate: Candidate?) {
        window.addLast(candidate)
        while (window.size > windowSize) window.removeFirst()
    }

    fun clear() = window.clear()

    /** Valeur la plus fréquente, même sous le seuil (voir [Vote.accepted]) ; null si aucune. */
    fun tally(): Vote? {
        val present = window.filterNotNull()
        if (present.isEmpty()) return null
        val (value, group) = present.groupBy { it.value }
            .maxWith(compareBy<Map.Entry<String, List<Candidate>>>({ it.value.size }, { e -> e.value.sumOf { it.confidence.toDouble() } }))
            .let { it.key to it.value }
        val raw = group.groupingBy { it.rawValue }.eachCount().maxBy { it.value }.key
        val votes = group.size
        return Vote(
            value = value,
            rawValue = raw,
            votes = votes,
            considered = present.size,
            windowFrames = window.size,
            confidence = group.map { it.confidence }.average().toFloat(),
            accepted = votes >= minVotes && votes.toFloat() / present.size >= minShare,
        )
    }
}

data class ScanSnapshot(
    val ean: Vote? = null,
    val dyeLot: Vote? = null,
    val brand: Vote? = null,
    val colorCode: Vote? = null,
    val colorName: Vote? = null,
    val frames: Int = 0,
    /** Depuis combien de temps l'EAN et le bain retenus n'ont pas changé. */
    val stableForMs: Long = 0,
)

/**
 * Agrège les résultats frame par frame (code-barre + parseur) et décide quand la capture
 * automatique peut avoir lieu : EAN et bain acceptés et inchangés pendant [autoCaptureAfterMs].
 */
class ScanAccumulator(
    windowSize: Int = 15,
    minVotes: Int = 3,
    minShare: Float = 0.5f,
    private val autoCaptureAfterMs: Long = 1_000,
) {
    private val ean = CandidateVoter(windowSize, minVotes = 2, minShare = minShare)
    private val dyeLot = CandidateVoter(windowSize, minVotes, minShare)
    private val brand = CandidateVoter(windowSize, minVotes, minShare)
    private val colorCode = CandidateVoter(windowSize, minVotes, minShare)
    private val colorName = CandidateVoter(windowSize, minVotes, minShare)

    private var frames = 0
    private var stableKey: Pair<String, String>? = null
    private var stableSince = 0L
    private var last = ScanSnapshot()

    fun add(detectedEan: String?, result: LabelParseResult, timestampMs: Long): ScanSnapshot {
        frames++
        ean.add(detectedEan?.let { Candidate(it, 1f, -1) })
        dyeLot.add(result.dyeLot)
        brand.add(result.brand)
        colorCode.add(result.colorCode)
        colorName.add(result.colorName)

        val eanVote = ean.tally()
        val lotVote = dyeLot.tally()
        val key = if (eanVote?.accepted == true && lotVote?.accepted == true) eanVote.value to lotVote.value else null
        if (key != stableKey) {
            stableKey = key
            stableSince = timestampMs
        }
        last = ScanSnapshot(
            ean = eanVote,
            dyeLot = lotVote,
            brand = brand.tally(),
            colorCode = colorCode.tally(),
            colorName = colorName.tally(),
            frames = frames,
            stableForMs = if (key != null) timestampMs - stableSince else 0,
        )
        return last
    }

    fun snapshot(): ScanSnapshot = last

    fun isReadyForAutoCapture(snapshot: ScanSnapshot = last): Boolean =
        snapshot.ean?.accepted == true && snapshot.dyeLot?.accepted == true && snapshot.stableForMs >= autoCaptureAfterMs

    fun autoCaptureProgress(snapshot: ScanSnapshot = last): Float =
        (snapshot.stableForMs.toFloat() / autoCaptureAfterMs).coerceIn(0f, 1f)

    fun reset() {
        listOf(ean, dyeLot, brand, colorCode, colorName).forEach { it.clear() }
        frames = 0
        stableKey = null
        stableSince = 0
        last = ScanSnapshot()
    }
}
