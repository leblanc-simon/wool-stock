package io.leblanc.woolstock.platform

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Le fichier passe par le cache de l'app et le FileProvider déclaré dans le manifeste de :app.
 * Seul le dernier fichier partagé y reste : l'export précédent, avec les noms des destinataires,
 * est effacé.
 */
@Composable
actual fun rememberFileSharer(): FileSharer {
    val context = LocalContext.current
    return remember(context) {
        FileSharer { name, mimeType, bytes, chooserTitle ->
            val file = withContext(Dispatchers.IO) {
                val folder = File(context.cacheDir, "exports")
                folder.deleteRecursively()
                folder.mkdirs()
                folder.resolve(name).apply { writeBytes(bytes) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, chooserTitle))
        }
    }
}
