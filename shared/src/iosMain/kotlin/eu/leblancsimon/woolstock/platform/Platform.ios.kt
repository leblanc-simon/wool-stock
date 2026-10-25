package eu.leblancsimon.woolstock.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.preferredLanguages
import platform.posix.uname
import platform.posix.utsname

/** « Apple iPhone17,1 » : l'identifiant du modèle, comme « Google Pixel 6 » sur Android. */
@OptIn(ExperimentalForeignApi::class)
actual fun deviceName(): String = memScoped {
    val info = alloc<utsname>()
    uname(info.ptr)
    "Apple " + info.machine.toKString()
}

actual fun languageTag(): String = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"

actual fun formatDateTime(epochMillis: Long): String {
    val formatter = NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterShortStyle
    }
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}
