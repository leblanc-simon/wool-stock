package eu.leblancsimon.woolstock.parser

import java.io.File

/** Charge les fixtures JSON de `src/test/resources/labels` et rejoue le parseur + le vote comme l'app. */
object Fixtures {
    data class Loaded(val file: String, val dump: LabelDump)

    fun all(): List<Loaded> {
        val dir = Fixtures::class.java.getResource("/labels")?.toURI()?.let(::File)
            ?: error("Répertoire de fixtures introuvable")
        return dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .sortedBy { it.name }
            .map { Loaded(it.name, LabelDumps.decode(it.readText())) }
    }

    private const val FRAME_INTERVAL_MS = 200L

    /** Valeurs que l'app proposerait à la capture : le meilleur vote sur toutes les frames. */
    fun evaluate(dump: LabelDump, parser: LabelParser): ParsedFields {
        val accumulator = ScanAccumulator()
        var snapshot = ScanSnapshot()
        dump.frames.forEachIndexed { index, frame ->
            val ean = frame.ean ?: dump.ean
            val result = parser.parse(frame.lines.map { it.toOcrLine() }, ean)
            snapshot = accumulator.add(ean, result, index * FRAME_INTERVAL_MS)
        }
        return ParsedFields(
            dyeLot = snapshot.dyeLot?.value,
            dyeLotRaw = snapshot.dyeLot?.rawValue,
            brand = snapshot.brand?.value,
            colorCode = snapshot.colorCode?.value,
            colorName = snapshot.colorName?.value,
        )
    }

    enum class Outcome { OK, KO, UNCHECKED }

    /** Comparaison insensible à la casse, aux accents et aux espaces ; "" attend une absence de valeur. */
    fun compare(expected: String?, actual: String?): Outcome = when {
        expected == null -> Outcome.UNCHECKED
        expected.isEmpty() -> if (actual.isNullOrBlank()) Outcome.OK else Outcome.KO
        actual != null && canonical(expected) == canonical(actual) -> Outcome.OK
        else -> Outcome.KO
    }

    private fun canonical(value: String) = TextFold.fold(value).filterNot { it.isWhitespace() }
}
