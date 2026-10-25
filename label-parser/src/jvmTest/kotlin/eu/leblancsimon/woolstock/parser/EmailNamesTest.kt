package eu.leblancsimon.woolstock.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmailNamesTest {
    private fun guess(email: String) = EmailNames.guess(email)

    @Test
    fun `dots and underscores separate words`() {
        assertEquals("Marie Dupont", guess("marie.dupont@exemple.fr"))
        assertEquals("Marie Dupont", guess("MARIE_DUPONT@exemple.fr"))
    }

    @Test
    fun `compound names keep their hyphen`() {
        assertEquals("Jean-Luc Dupont", guess("jean-luc.dupont@exemple.fr"))
    }

    @Test
    fun `tags and digits are dropped`() {
        assertEquals("Lea Martin", guess("lea.martin+laine@exemple.fr"))
        assertEquals("Simon", guess("simon42@exemple.fr"))
    }

    @Test
    fun `accents are kept`() {
        assertEquals("Élodie", guess("élodie@exemple.fr"))
    }

    @Test
    fun `nothing usable gives an empty name`() {
        assertEquals("", guess("1234@exemple.fr"))
        assertEquals("", guess(""))
    }
}
