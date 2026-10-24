package eu.leblancsimon.woolstock.data

import android.content.Context
import androidx.core.content.edit
import eu.leblancsimon.woolstock.BuildConfig
import eu.leblancsimon.woolstock.data.api.ApiClient
import eu.leblancsimon.woolstock.data.api.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Réglages de l'appareil : adresse du serveur et connexion, gardée sans limite de durée. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER_URL, null) ?: BuildConfig.DEFAULT_API_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _session = MutableStateFlow(
        prefs.getString(KEY_SESSION, null)?.let { runCatching { ApiClient.json.decodeFromString<Session>(it) }.getOrNull() },
    )

    /** null tant que personne n'est connecté sur ce téléphone. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun saveServerUrl(serverUrl: String) {
        prefs.edit { putString(KEY_SERVER_URL, serverUrl) }
        _serverUrl.value = serverUrl
    }

    fun saveSession(session: Session) {
        prefs.edit { putString(KEY_SESSION, ApiClient.json.encodeToString(session)) }
        _session.value = session
    }

    fun clearSession() {
        prefs.edit { remove(KEY_SESSION) }
        _session.value = null
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SESSION = "session"
    }
}
