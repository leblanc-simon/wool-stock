package eu.leblancsimon.woolstock.stock

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import eu.leblancsimon.woolstock.data.api.ExportFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Propose le classeur Excel téléchargé au partage : l'ouvrir dans un tableur, l'envoyer par
 * e-mail, l'enregistrer dans Drive… au choix de la personne, dans la fenêtre du système.
 */
object StockExport {
    suspend fun share(context: Context, export: ExportFile, chooserTitle: String) {
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "exports").apply { mkdirs() }
                .resolve(export.name)
                .apply { writeBytes(export.bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ExportFile.TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}
