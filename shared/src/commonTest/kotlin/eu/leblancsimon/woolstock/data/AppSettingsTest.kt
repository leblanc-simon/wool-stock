package eu.leblancsimon.woolstock.data

import eu.leblancsimon.woolstock.data.api.Session
import eu.leblancsimon.woolstock.data.api.User
import eu.leblancsimon.woolstock.platform.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore(val values: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

class AppSettingsTest {
    private val marie = Session("jeton-a", User(1, "marie@example.org", "Marie", "manager"))

    @Test
    fun theTokenOnlyGoesToTheServerThatIssuedIt() {
        val settings = AppSettings(MemoryStore(), "https://stock.example")
        settings.saveSession(marie)

        assertEquals("jeton-a", settings.token("https://stock.example"))
        assertEquals("jeton-a", settings.token("https://stock.example/"))
        assertNull(settings.token("https://autre.example"))
    }

    @Test
    fun anotherServerAddressForgetsTheSession() {
        val store = MemoryStore()
        val settings = AppSettings(store, "https://stock.example")
        settings.saveSession(marie)

        // La même adresse, réenregistrée : rien ne change.
        settings.saveServerUrl("https://stock.example/")
        assertEquals(marie, settings.session.value)

        settings.saveServerUrl("https://autre.example")
        assertNull(settings.session.value)
        assertNull(settings.token("https://autre.example"))
        assertNull(store.getString("session"))
        // Rouvert, le téléphone n'est connecté nulle part.
        assertNull(AppSettings(store, "https://stock.example").session.value)
    }

    @Test
    fun aSessionSurvivesARestartBoundToItsServer() {
        val store = MemoryStore()
        AppSettings(store, "https://stock.example").saveSession(marie)

        val reopened = AppSettings(store, "https://stock.example")
        assertEquals(marie, reopened.session.value)
        assertEquals("jeton-a", reopened.token("https://stock.example"))
    }

    @Test
    fun aSessionFromAPreviousVersionIsBoundToTheSavedServer() {
        val store = MemoryStore(
            mutableMapOf(
                "server_url" to "https://stock.example",
                "session" to """{"token":"jeton-a","user":{"id":1,"email":"marie@example.org","name":"Marie","role":"manager"}}""",
            ),
        )
        val settings = AppSettings(store, "https://defaut.example")

        assertEquals(marie, settings.session.value)
        assertEquals("jeton-a", settings.token("https://stock.example"))
        assertTrue(store.getString("session")!!.contains("\"server_url\":\"https://stock.example\""))
    }

    @Test
    fun aSessionKeptOutsideTheSecretsMovesIntoThem() {
        val store = MemoryStore()
        AppSettings(store, "https://stock.example").saveSession(marie)
        val secrets = MemoryStore()

        val settings = AppSettings(store, "https://stock.example", secrets)

        assertEquals(marie, settings.session.value)
        assertNull(store.getString("session"))
        assertTrue(secrets.getString("session") != null)
        settings.clearSession()
        assertNull(secrets.getString("session"))
    }
}
