package eu.leblancsimon.woolstock.data

import eu.leblancsimon.woolstock.data.api.ApiClient
import eu.leblancsimon.woolstock.data.api.Session
import eu.leblancsimon.woolstock.data.api.User
import eu.leblancsimon.woolstock.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

/**
 * Réglages de l'appareil : adresse du serveur et connexion. [defaultServerUrl] sert tant
 * qu'aucune adresse n'a été enregistrée. Le jeton de session est gardé dans [secrets] (le
 * trousseau sur iOS ; sur Android, [store] lui-même, exclu des sauvegardes et des transferts)
 * et ne vaut que pour le serveur qui l'a ouvert : changer d'adresse l'oublie.
 */
class AppSettings(
    private val store: KeyValueStore,
    defaultServerUrl: String,
    private val secrets: KeyValueStore = store,
) {
    private val _serverUrl = MutableStateFlow(store.getString(KEY_SERVER_URL) ?: defaultServerUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    /** Le serveur qui a ouvert la session, seul à recevoir son jeton. */
    @Volatile
    private var sessionServer: String? = null

    private val _session = MutableStateFlow(loadSession())

    /** null tant que personne n'est connecté sur ce téléphone. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    /**
     * Enregistre l'adresse du serveur. Une session ne vaut que pour le serveur qui l'a ouverte :
     * une autre adresse l'oublie, pour que son jeton ne parte jamais vers un autre serveur.
     */
    fun saveServerUrl(serverUrl: String) {
        if (!isCurrentServer(serverUrl)) clearSession()
        store.putString(KEY_SERVER_URL, serverUrl)
        _serverUrl.value = serverUrl
    }

    /** Garde la session, ouverte sur le serveur actuel. */
    fun saveSession(session: Session) {
        val server = normalize(_serverUrl.value)
        persist(server, session)
        sessionServer = server
        _session.value = session
    }

    fun clearSession() {
        secrets.remove(KEY_SESSION)
        sessionServer = null
        _session.value = null
    }

    /** [url] désigne-t-elle le serveur enregistré ? */
    fun isCurrentServer(url: String): Boolean = normalize(url) == normalize(_serverUrl.value)

    /** Jeton à présenter à [serverUrl] : celui de la session, seulement sur le serveur qui l'a ouverte. */
    fun token(serverUrl: String): String? = _session.value?.token?.takeIf { sessionServer == normalize(serverUrl) }

    /**
     * Relit la session enregistrée. Celle des versions précédentes, sans adresse, a été ouverte
     * sur le serveur enregistré ; gardée hors de [secrets] (NSUserDefaults sur iOS), elle y passe.
     */
    private fun loadSession(): Session? {
        val legacy = if (secrets !== store) store.getString(KEY_SESSION) else null
        if (legacy != null) store.remove(KEY_SESSION)
        val saved = secrets.getString(KEY_SESSION)
        val raw = saved ?: legacy ?: return null
        val stored = runCatching { ApiClient.json.decodeFromString<StoredSession>(raw) }.getOrNull() ?: return null
        val server = stored.serverUrl ?: normalize(_serverUrl.value)
        val session = Session(stored.token, stored.user)
        if (saved == null || stored.serverUrl == null) persist(server, session)
        sessionServer = server
        return session
    }

    private fun persist(server: String, session: Session) {
        secrets.putString(KEY_SESSION, ApiClient.json.encodeToString(StoredSession(server, session.token, session.user)))
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SESSION = "session"

        /** Une adresse se compare sans espaces ni barre finale, comme l'emploie le client. */
        fun normalize(url: String) = url.trim().trimEnd('/')
    }
}

/** Session enregistrée : celle du serveur, et l'adresse du serveur qui l'a ouverte. */
@Serializable
private data class StoredSession(
    @SerialName("server_url") val serverUrl: String? = null,
    val token: String,
    val user: User,
)
