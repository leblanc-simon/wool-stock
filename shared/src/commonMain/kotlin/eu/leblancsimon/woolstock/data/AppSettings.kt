package eu.leblancsimon.woolstock.data

import eu.leblancsimon.woolstock.data.api.ApiClient
import eu.leblancsimon.woolstock.data.api.Session
import eu.leblancsimon.woolstock.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Réglages de l'appareil : adresse du serveur et connexion, gardée sans limite de durée.
 * [defaultServerUrl] sert tant qu'aucune adresse n'a été enregistrée.
 */
class AppSettings(private val store: KeyValueStore, defaultServerUrl: String) {
    private val _serverUrl = MutableStateFlow(store.getString(KEY_SERVER_URL) ?: defaultServerUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _session = MutableStateFlow(
        store.getString(KEY_SESSION)?.let { runCatching { ApiClient.json.decodeFromString<Session>(it) }.getOrNull() },
    )

    /** null tant que personne n'est connecté sur ce téléphone. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun saveServerUrl(serverUrl: String) {
        store.putString(KEY_SERVER_URL, serverUrl)
        _serverUrl.value = serverUrl
    }

    fun saveSession(session: Session) {
        store.putString(KEY_SESSION, ApiClient.json.encodeToString(session))
        _session.value = session
    }

    fun clearSession() {
        store.remove(KEY_SESSION)
        _session.value = null
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SESSION = "session"
    }
}
