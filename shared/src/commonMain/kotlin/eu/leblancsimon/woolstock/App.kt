package eu.leblancsimon.woolstock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import eu.leblancsimon.woolstock.ui.WoolStockNavHost
import eu.leblancsimon.woolstock.ui.WoolStockTheme

/** L'application entière, montée par l'activité Android ou le contrôleur de vue iOS. */
@Composable
fun WoolStockContent(container: AppContainer) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        WoolStockTheme {
            WoolStockNavHost()
        }
    }
}
