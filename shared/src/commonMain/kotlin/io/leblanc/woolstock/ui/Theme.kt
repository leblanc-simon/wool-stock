package io.leblanc.woolstock.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun WoolStockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = platformColorScheme(isSystemInDarkTheme()), content = content)
}

/** Couleurs dynamiques du fond d'écran sur Android 12 et plus, palette Material par défaut sinon. */
@Composable
internal expect fun platformColorScheme(dark: Boolean): ColorScheme
