package eu.leblancsimon.woolstock.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

/**
 * Le fichier passe par le dossier temporaire de l'app, puis la feuille de partage d'iOS
 * (Fichiers, Mail, Numbers…). iOS déduit le type de l'extension et n'affiche pas de titre.
 */
@Composable
actual fun rememberFileSharer(): FileSharer = remember {
    FileSharer { name, _, bytes, _ ->
        val url = withContext(Dispatchers.IO) { writeTemporary(name, bytes) }
        val presenter = topViewController() ?: return@FileSharer
        val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
        // Sur iPad, la feuille s'ouvre en bulle : il lui faut un point d'ancrage.
        sheet.popoverPresentationController?.sourceView = presenter.view
        presenter.presentViewController(sheet, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun writeTemporary(name: String, bytes: ByteArray): NSURL {
    val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + name)
    val data = if (bytes.isEmpty()) {
        NSData()
    } else {
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }
    if (!data.writeToURL(url, atomically = true)) throw IOException("écriture impossible : $name")
    return url
}

/** Le contrôleur affiché au premier plan, d'où présenter la feuille de partage. */
private fun topViewController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstNotNullOfOrNull { it.keyWindow }
    var controller = window?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}
