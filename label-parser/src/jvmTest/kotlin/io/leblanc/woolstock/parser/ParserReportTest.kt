package io.leblanc.woolstock.parser

import io.leblanc.woolstock.parser.Fixtures.Outcome
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertTrue

/**
 * Génère `build/reports/label-parser/report.md` : taux de réussite par champ et par jeu
 * (synthétique / réel), détail par étiquette, et liste des exports encore à annoter.
 * Critère du POC : bain correct sur au moins 70 % des étiquettes réelles annotées.
 */
class ParserReportTest {
    private val parser = LabelParser()

    private data class Row(
        val file: String,
        val dump: LabelDump,
        val expected: ExpectedFields,
        val actual: ParsedFields,
    ) {
        val fields = listOf(
            Triple("Bain", expected.dyeLot, actual.dyeLot),
            Triple("Marque", expected.brand, actual.brand),
            Triple("Coloris n°", expected.colorCode, actual.colorCode),
            Triple("Coloris nom", expected.colorName, actual.colorName),
        )
        val dyeLotOutcome get() = Fixtures.compare(expected.dyeLot, actual.dyeLot)
    }

    @Test
    fun generateReport() {
        val all = Fixtures.all()
        val rows = all.mapNotNull { loaded ->
            val expected = loaded.dump.expected ?: return@mapNotNull null
            Row(loaded.file, loaded.dump, expected, Fixtures.evaluate(loaded.dump, parser))
        }
        val toAnnotate = all.filter { it.dump.expected == null }

        val report = buildString {
            appendLine("# Rapport du parseur d'étiquettes")
            appendLine()
            appendLine("Généré le ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}.")
            appendLine("Valeurs comparées : meilleur vote sur l'ensemble des frames de chaque fixture.")
            appendLine()
            appendLine("## Synthèse")
            appendLine()
            appendLine("| Jeu | Étiquettes | Bain | Marque | Coloris n° | Coloris nom |")
            appendLine("|---|---|---|---|---|---|")
            for (source in listOf(LabelDump.SOURCE_REAL, LabelDump.SOURCE_SYNTHETIC)) {
                val subset = rows.filter { it.dump.source == source }
                val rates = (0 until 4).joinToString(" | ") { i -> rate(subset.map { it.fields[i] }) }
                appendLine("| $source | ${subset.size} | $rates |")
            }
            appendLine()
            appendLine("## Détail")
            appendLine()
            appendLine("| Fixture | Jeu | Bain attendu | Bain proposé (lu) | Marque | Coloris n° | Coloris nom |")
            appendLine("|---|---|---|---|---|---|---|")
            for (row in rows.sortedWith(compareBy({ it.dump.source }, { it.file }))) {
                val lot = "${mark(row.dyeLotOutcome)} ${row.actual.dyeLot ?: "—"}" +
                    (row.actual.dyeLotRaw?.takeIf { it != row.actual.dyeLot }?.let { " ($it)" } ?: "")
                val others = row.fields.drop(1).joinToString(" | ") { (_, want, got) ->
                    "${mark(Fixtures.compare(want, got))} ${got ?: "—"}"
                }
                appendLine("| ${row.dump.id} | ${row.dump.source} | ${row.expected.dyeLot ?: "—"} | $lot | $others |")
            }
            if (toAnnotate.isNotEmpty()) {
                appendLine()
                appendLine("## À annoter")
                appendLine()
                appendLine("Exports sans champ `expected` : recopier les valeurs correctes depuis `parsed` puis corriger.")
                appendLine()
                toAnnotate.forEach { appendLine("- `${it.file}`") }
            }
        }

        val dir = File(System.getProperty("labelReportDir") ?: "build/reports/label-parser").apply { mkdirs() }
        File(dir, "report.md").writeText(report)
        println(report)

        val real = rows.filter { it.dump.source == LabelDump.SOURCE_REAL && it.dyeLotOutcome != Outcome.UNCHECKED }
        if (real.isNotEmpty()) {
            val success = real.count { it.dyeLotOutcome == Outcome.OK }.toFloat() / real.size
            assertTrue(success >= DYE_LOT_TARGET, "Bain correct sur ${"%.0f".format(success * 100)} % des étiquettes réelles (objectif 70 %)")
        }
    }

    private fun rate(fields: List<Triple<String, String?, String?>>): String {
        val checked = fields.map { (_, want, got) -> Fixtures.compare(want, got) }.filter { it != Outcome.UNCHECKED }
        if (checked.isEmpty()) return "—"
        val ok = checked.count { it == Outcome.OK }
        return "$ok/${checked.size} (${ok * 100 / checked.size} %)"
    }

    private fun mark(outcome: Outcome) = when (outcome) {
        Outcome.OK -> "✅"
        Outcome.KO -> "❌"
        Outcome.UNCHECKED -> "·"
    }

    private companion object {
        const val DYE_LOT_TARGET = 0.7f
    }
}
