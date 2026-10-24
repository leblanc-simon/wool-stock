package eu.leblancsimon.woolstock

import android.app.Application
import android.content.Context
import eu.leblancsimon.woolstock.data.AppSettings
import eu.leblancsimon.woolstock.data.StockRepository
import eu.leblancsimon.woolstock.data.WoolStockDatabase
import eu.leblancsimon.woolstock.data.api.ApiClient
import eu.leblancsimon.woolstock.parser.BrandDictionary
import eu.leblancsimon.woolstock.parser.LabelParser

/** Conteneur de dépendances minimal (pas de Hilt pour un POC). */
class AppContainer(context: Context) {
    val settings by lazy { AppSettings(context) }
    private val brandDictionary by lazy { BrandDictionary.default() }
    val stockRepository by lazy {
        StockRepository(
            WoolStockDatabase.build(context),
            ApiClient(
                baseUrl = { settings.serverUrl.value },
                token = { settings.session.value?.token },
                onUnauthorized = { settings.clearSession() },
            ),
            settings,
            dictionaryBrands = { brandDictionary.entries.map { it.name } },
        )
    }
    val labelParser by lazy { LabelParser(brandDictionary) }
}

class WoolStockApp : Application() {
    val container by lazy { AppContainer(this) }
}
