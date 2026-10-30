package io.leblanc.woolstock.confirm

import kotlin.uuid.Uuid

/**
 * Identifiant d'envoi (request_id) d'une saisie, lié à son contenu. Renvoyée telle quelle
 * (réseau coupé avant la réponse), la saisie garde son identifiant : le serveur ne la compte
 * qu'une fois. Corrigée, elle en reçoit un nouveau, sans quoi le serveur la refuserait
 * (error.request_reused). [content] et [id] reprennent le dernier couple après une recréation
 * de l'écran.
 */
internal class RequestIds(
    content: String? = null,
    id: String? = null,
    private val newId: () -> String = { Uuid.random().toString() },
) {
    var content: String? = content
        private set
    var id: String? = id
        private set

    /** L'identifiant de cette saisie : le même tant que [content] ne change pas. */
    fun idFor(content: String): String {
        val current = id
        if (current != null && content == this.content) return current
        return newId().also {
            this.content = content
            id = it
        }
    }
}
