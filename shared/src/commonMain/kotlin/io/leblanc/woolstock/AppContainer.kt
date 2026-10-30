package io.leblanc.woolstock

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.room.RoomDatabase
import io.leblanc.woolstock.data.AppSettings
import io.leblanc.woolstock.data.StockRepository
import io.leblanc.woolstock.data.WoolStockDatabase
import io.leblanc.woolstock.data.api.ApiClient
import io.leblanc.woolstock.parser.BrandDictionary
import io.leblanc.woolstock.parser.LabelParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Conteneur de dépendances minimal (pas de framework d'injection pour un POC), créé une fois
 * par l'application Android ou iOS avec ce qui dépend de la plateforme : où garder les
 * réglages, comment ouvrir la base locale, l'identifiant et la version de l'app
 * (io.leblanc.woolstock, 0.8.0), qui forment le User-Agent des appels au serveur, et si HTTP
 * en clair est permis ([allowCleartext] : versions de développement seulement).
 */
class AppContainer(
    val settings: AppSettings,
    databaseBuilder: () -> RoomDatabase.Builder<WoolStockDatabase>,
    appId: String,
    appVersion: String,
    val allowCleartext: Boolean,
) {
    private val userAgent = "$appId v$appVersion"

    /** Ce qui doit aboutir même si l'écran qui l'a lancé disparaît. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                token = settings::token,
                // Session refusée (compte retiré, sessions fermées) : on oublie aussi la copie du cache.
                onUnauthorized = {
                    settings.clearSession()
                    scope.launch { database.yarnDao().clearReferences() }
                },
                allowCleartext = allowCleartext,
                userAgent = userAgent,
            ),
            settings,
            dictionaryBrands = { brandDictionary.entries.map { it.name } },
        )
    }
    val labelParser by lazy { LabelParser(brandDictionary) }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer absent : WoolStockContent le fournit") }

