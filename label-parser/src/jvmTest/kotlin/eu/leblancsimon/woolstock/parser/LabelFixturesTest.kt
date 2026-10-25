package eu.leblancsimon.woolstock.parser

import eu.leblancsimon.woolstock.parser.Fixtures.Outcome
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertTrue
import kotlin.test.fail

/** Chaque fixture synthétique doit être entièrement reconnue. */
class LabelFixturesTest {
    private val parser = LabelParser()

    @TestFactory
    fun syntheticFixtures(): List<DynamicTest> {
        val synthetic = Fixtures.all().filter { it.dump.source == LabelDump.SOURCE_SYNTHETIC }
        assertTrue(synthetic.size >= 5, "Au moins 5 fixtures synthétiques attendues")
        return synthetic.map { loaded ->
            DynamicTest.dynamicTest(loaded.dump.id) {
                val expected = loaded.dump.expected ?: fail("${loaded.file} : champ expected manquant")
                val actual = Fixtures.evaluate(loaded.dump, parser)
                val mismatches = listOf(
                    Triple("dyeLot", expected.dyeLot, actual.dyeLot),
                    Triple("brand", expected.brand, actual.brand),
                    Triple("colorCode", expected.colorCode, actual.colorCode),
                    Triple("colorName", expected.colorName, actual.colorName),
                ).filter { (_, want, got) -> Fixtures.compare(want, got) == Outcome.KO }
                if (mismatches.isNotEmpty()) {
                    fail(mismatches.joinToString("; ", prefix = "${loaded.file} : ") { (field, want, got) ->
                        "$field attendu « $want », obtenu « $got »"
                    })
                }
            }
        }
    }
}
