package eu.leblancsimon.woolstock.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/** iOS n'a pas de couleurs dynamiques : la palette Material, claire ou sombre selon le système. */
@Composable
internal actual fun platformColorScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme() else lightColorScheme()
