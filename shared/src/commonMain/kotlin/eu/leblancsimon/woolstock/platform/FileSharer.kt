package eu.leblancsimon.woolstock.platform

import androidx.compose.runtime.Composable

/**
 * Propose un fichier à la fenêtre de partage du système : l'ouvrir dans un tableur, l'envoyer
 * par e-mail, l'enregistrer… au choix de la personne.
 */
fun interface FileSharer {
    suspend fun share(name: String, mimeType: String, bytes: ByteArray, chooserTitle: String)
}

@Composable
expect fun rememberFileSharer(): FileSharer
