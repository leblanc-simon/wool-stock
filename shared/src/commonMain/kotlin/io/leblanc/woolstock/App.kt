package io.leblanc.woolstock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.leblanc.woolstock.ui.WoolStockNavHost
import io.leblanc.woolstock.ui.WoolStockTheme

/** L'application entière, montée par l'activité Android ou le contrôleur de vue iOS. */
@Composable
fun WoolStockContent(container: AppContainer) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        WoolStockTheme {
            WoolStockNavHost()
        }
    }
}
