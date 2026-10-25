package eu.leblancsimon.woolstock

import androidx.compose.ui.window.ComposeUIViewController
import eu.leblancsimon.woolstock.data.AppSettings
import eu.leblancsimon.woolstock.data.iosDatabaseBuilder
import eu.leblancsimon.woolstock.platform.UserDefaultsStore
import platform.UIKit.UIViewController

/**
 * Point d'entrée de l'app iOS, appelé une fois par iosApp (ContentView.swift). [defaultServerUrl]
 * vient de la clé WoolStockApiUrl de l'Info.plist : il sert tant qu'aucune adresse n'a été
 * enregistrée dans les réglages.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(defaultServerUrl: String): UIViewController {
    val container = AppContainer(
        settings = AppSettings(UserDefaultsStore(), defaultServerUrl),
        databaseBuilder = ::iosDatabaseBuilder,
    )
    return ComposeUIViewController { WoolStockContent(container) }
}
