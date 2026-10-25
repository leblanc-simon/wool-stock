package eu.leblancsimon.woolstock

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.room.RoomDatabase
import eu.leblancsimon.woolstock.data.AppSettings
import eu.leblancsimon.woolstock.data.StockRepository
import eu.leblancsimon.woolstock.data.WoolStockDatabase
import eu.leblancsimon.woolstock.data.api.ApiClient
import eu.leblancsimon.woolstock.parser.BrandDictionary
import eu.leblancsimon.woolstock.parser.LabelParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Conteneur de dépendances minimal (pas de framework d'injection pour un POC), créé une fois
 * par l'application Android ou iOS avec ce qui dépend de la plateforme : où garder les
 * réglages, et comment ouvrir la base locale.
 */
class AppContainer(
    val settings: AppSettings,
    databaseBuilder: () -> RoomDatabase.Builder<WoolStockDatabase>,
) {
    private val brandDictionary by lazy { BrandDictionary.default() }
    private val database by lazy {
        databaseBuilder()
            .addMigrations(WoolStockDatabase.MIGRATION_1_2)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
    val stockRepository by lazy {
        StockRepository(
            database,
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

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer absent : WoolStockContent le fournit") }

