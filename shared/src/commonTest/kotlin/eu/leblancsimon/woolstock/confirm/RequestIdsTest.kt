package eu.leblancsimon.woolstock.confirm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RequestIdsTest {
    @Test
    fun aResentEntryKeepsItsIdAndACorrectedOneGetsANewOne() {
        val ids = RequestIds()
        val first = ids.idFor("ENTRY|8435407948594|81784|3")

        // Renvoi après une coupure : le même identifiant, le serveur ne compte qu'une fois.
        assertEquals(first, ids.idFor("ENTRY|8435407948594|81784|3"))
        // Quantité corrigée : une autre saisie.
        val corrected = ids.idFor("ENTRY|8435407948594|81784|5")
        assertNotEquals(first, corrected)
        assertEquals(corrected, ids.idFor("ENTRY|8435407948594|81784|5"))
    }

    @Test
    fun theLastIdSurvivesTheScreenBeingRecreated() {
        val ids = RequestIds()
        val id = ids.idFor("EXIT|8435407948594|81784|2|Marie")

        val restored = RequestIds(ids.content, ids.id)
        assertEquals(id, restored.idFor("EXIT|8435407948594|81784|2|Marie"))
    }
}
