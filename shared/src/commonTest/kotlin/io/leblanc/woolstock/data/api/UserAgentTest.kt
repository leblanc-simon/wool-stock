package io.leblanc.woolstock.data.api

import io.leblanc.woolstock.data.api.ApiClient.Companion.woolStockDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UserAgentTest {
    @Test
    fun everyCallNamesTheAppAndItsVersion() = runTest {
        val agents = mutableListOf<String?>()
        val http = HttpClient(
            MockEngine { request ->
                agents += request.headers[HttpHeaders.UserAgent]
                respond("""{"status":"ok","version":"test"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { woolStockDefaults("io.leblanc.woolstock v0.8.0") }
        val api = ApiClient(baseUrl = { "https://stock.example" }, http = http)

        api.health()
        // Le test d'un autre serveur, depuis les réglages, garde le même client.
        api.at("https://autre.example").health()

        assertEquals(listOf<String?>("io.leblanc.woolstock v0.8.0", "io.leblanc.woolstock v0.8.0"), agents)
    }
}
