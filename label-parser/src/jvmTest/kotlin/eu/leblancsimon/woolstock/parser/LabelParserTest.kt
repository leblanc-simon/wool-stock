package eu.leblancsimon.woolstock.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LabelParserTest {
    private val parser = LabelParser()

    private fun line(text: String, top: Int = 100, left: Int = 100, height: Int = 40) =
        OcrLine(text, Rect(left, top, left + text.length * 22, top + height))

    @Test
    fun `mots-clés insensibles à la casse, aux accents et à une erreur OCR`() {
        assertEquals("4512", parser.parse(listOf(line("FÄRBEPARTIE 4512"))).dyeLot?.value)
        assertEquals("4512", parser.parse(listOf(line("farbepartie: 4512"))).dyeLot?.value)
        assertEquals("4512", parser.parse(listOf(line("Partle 4512"))).dyeLot?.value)
        assertEquals("4512", parser.parse(listOf(line("DYE L0T 4512"))).dyeLot?.value)
        assertEquals("4512", parser.parse(listOf(line("Lot No. 4512"))).dyeLot?.value)
        assertEquals("4512", parser.parse(listOf(line("N° de bain : 4512"))).dyeLot?.value)
    }

    @Test
    fun `Batchnr annonce le bain, en un mot ou en deux`() {
        assertEquals("23051", parser.parse(listOf(line("Batchnr: 23051"))).dyeLot?.value)
        assertEquals("23051", parser.parse(listOf(line("BATCHNR. 23051"))).dyeLot?.value)
        assertEquals("23051", parser.parse(listOf(line("Batch-Nr. 23051"))).dyeLot?.value)
        assertEquals("23051", parser.parse(listOf(line("Batchnf 23051"))).dyeLot?.value)
    }

    @Test
    fun `saute les préfixes N°, No, Nr`() {
        assertEquals("A23", parser.parse(listOf(line("Bain N°A23"))).dyeLot?.value)
        assertEquals("812", parser.parse(listOf(line("Partie-Nr.: 812"))).dyeLot?.value)
    }

    @Test
    fun `bain prioritaire sur lot, plus ambigu`() {
        val result = parser.parse(listOf(line("Lot 10 pelotes", top = 100), line("Bain 5521", top = 300)))
        assertEquals("5521", result.dyeLot?.value)
    }

    @Test
    fun `lavage à la main n'est pas un bain`() {
        assertNull(parser.parse(listOf(line("Lavage à la main 30"))).dyeLot)
    }

    @Test
    fun `ne donne jamais la même valeur au bain et au coloris`() {
        val result = parser.parse(
            listOf(
                OcrLine("Coloris/Bain", Rect(100, 100, 400, 140)),
                OcrLine("4512", Rect(110, 150, 220, 190)),
            ),
        )
        assertEquals(listOf("4512"), listOfNotNull(result.dyeLot?.value, result.colorCode?.value))
    }

    @Test
    fun `garde la valeur brute après correction`() {
        val lot = parser.parse(listOf(line("Bain : 4O12"))).dyeLot
        assertEquals("4012", lot?.value)
        assertEquals("4O12", lot?.rawValue)
    }

    @Test
    fun `nom de coloris avant le numéro`() {
        val result = parser.parse(listOf(line("Colour: Navy Blue 0215")))
        assertEquals("0215", result.colorCode?.value)
        assertEquals("Navy Blue", result.colorName?.value)
    }

    @Test
    fun `marque floue avec bonus gros caractères`() {
        val result = parser.parse(
            listOf(
                OcrLine("SCHACHENMAYR", Rect(100, 50, 600, 130)),
                line("Rowan style", top = 200),
                line("Bain 12", top = 260),
            ),
        )
        assertEquals("Schachenmayr", result.brand?.value)
    }

    @Test
    fun `ligne vide ou sans mot-clé`() {
        assertEquals(LabelParseResult.EMPTY, parser.parse(emptyList()))
        assertNull(parser.parse(listOf(line("100% laine mérinos"))).dyeLot)
    }
}
