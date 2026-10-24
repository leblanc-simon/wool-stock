package eu.leblancsimon.woolstock.scan

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import eu.leblancsimon.woolstock.parser.LabelDump
import eu.leblancsimon.woolstock.parser.LabelDumps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Partage le relevé OCR en JSON, à déposer tel quel dans `label-parser/src/test/resources/labels`. */
object OcrExport {
    suspend fun share(context: Context, dump: LabelDump, chooserTitle: String) {
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "exports").apply { mkdirs() }
                .resolve("${dump.id}.json")
                .apply { writeText(LabelDumps.encode(dump)) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}
