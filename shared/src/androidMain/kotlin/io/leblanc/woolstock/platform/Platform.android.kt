package io.leblanc.woolstock.platform

import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

actual fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

actual fun languageTag(): String = Locale.getDefault().toLanguageTag()

actual fun formatDateTime(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
