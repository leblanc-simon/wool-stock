package eu.leblancsimon.woolstock.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionsTest {
    private val brands = listOf("Stylecraft", "Bergère de France", "Drops", "Katia", "Lang Yarns", "stylecraft")

    @Test
    fun `a different case proposes the known spelling first`() {
        assertEquals("Stylecraft", Suggestions.suggest("stylecraft", brands).first())
    }

    @Test
    fun `duplicates keep the first spelling`() {
        val all = Suggestions.suggest("", brands, limit = 10)
        assertEquals(listOf("Stylecraft", "Bergère de France", "Drops", "Katia", "Lang Yarns"), all)
    }

    @Test
    fun `accents and word starts match`() {
        assertEquals(listOf("Bergère de France"), Suggestions.suggest("berge", brands))
        assertEquals(listOf("Bergère de France"), Suggestions.suggest("france", brands))
        assertEquals(listOf("Lang Yarns"), Suggestions.suggest("yarn", brands))
    }

    @Test
    fun `prefix ranks before contains`() {
        val names = listOf("rouge cerise", "rose", "framboise rosée")
        assertEquals(listOf("rose", "framboise rosée"), Suggestions.suggest("ros", names))
    }

    @Test
    fun `one typo is tolerated from four letters`() {
        assertEquals(listOf("Stylecraft"), Suggestions.suggest("stylcraft", brands))
        assertEquals(listOf("Katia"), Suggestions.suggest("kata", brands))
        assertTrue(Suggestions.suggest("dro", listOf("Drops", "Debbie")).contains("Drops"))
        assertTrue(Suggestions.suggest("zzz", brands).isEmpty())
    }

    @Test
    fun `the exact input is not proposed again`() {
        assertTrue(Suggestions.suggest("Katia", brands).isEmpty())
    }

    @Test
    fun `same value ignores case accents and spaces`() {
        assertTrue(Suggestions.sameValue(" bergere  de france", "Bergère de France"))
    }
}
