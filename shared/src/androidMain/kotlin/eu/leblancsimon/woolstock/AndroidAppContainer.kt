package eu.leblancsimon.woolstock

import android.content.Context
import eu.leblancsimon.woolstock.data.AppSettings
import eu.leblancsimon.woolstock.data.androidDatabaseBuilder
import eu.leblancsimon.woolstock.platform.SharedPreferencesStore

/** Le conteneur de l'app Android, avec les réglages et la base des versions précédentes : la mise à jour garde la connexion. */
fun androidAppContainer(context: Context, defaultServerUrl: String): AppContainer {
    val appContext = context.applicationContext
    return AppContainer(
        settings = AppSettings(SharedPreferencesStore(appContext), defaultServerUrl),
        databaseBuilder = { androidDatabaseBuilder(appContext) },
    )
}
