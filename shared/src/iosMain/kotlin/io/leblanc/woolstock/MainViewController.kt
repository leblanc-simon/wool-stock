package io.leblanc.woolstock

import androidx.compose.ui.window.ComposeUIViewController
import io.leblanc.woolstock.data.AppSettings
import io.leblanc.woolstock.data.iosDatabaseBuilder
import io.leblanc.woolstock.platform.KeychainStore
import io.leblanc.woolstock.platform.UserDefaultsStore
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * Point d'entrée de l'app iOS, appelé une fois par iosApp (ContentView.swift). [defaultServerUrl]
 * vient de la clé WoolStockApiUrl de l'Info.plist : il sert tant qu'aucune adresse n'a été
 * enregistrée dans les réglages. Identifiant et version sont ceux du bundle (Config.xcconfig).
 * Le jeton de session va dans le trousseau ; HTTP en clair n'est permis qu'en Debug, comme la
 * version debug d'Android.
 */
@OptIn(ExperimentalNativeApi::class)
@Suppress("FunctionName", "unused")
fun MainViewController(defaultServerUrl: String): UIViewController {
    val bundle = NSBundle.mainBundle
    val appId = bundle.bundleIdentifier ?: "io.leblanc.woolstock"
    val container = AppContainer(
        settings = AppSettings(UserDefaultsStore(), defaultServerUrl, secrets = KeychainStore(service = appId)),
        databaseBuilder = ::iosDatabaseBuilder,
        appId = appId,
        appVersion = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?",
        allowCleartext = Platform.isDebugBinary,
    )
    return ComposeUIViewController { WoolStockContent(container) }
}
