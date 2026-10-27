package eu.leblancsimon.woolstock.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiClientTest {
    private val sent = mutableListOf<String>()
    private val authorizations = mutableListOf<String?>()

    /** Un serveur qui répond [body] avec [type] à toute requête, et note ce qu'il reçoit. */
    private fun client(
        body: String,
        type: String = "application/json",
        allowCleartext: Boolean = false,
        baseUrl: () -> String = { "https://stock.example" },
        token: (String) -> String? = { null },
    ) = ApiClient(
        baseUrl = baseUrl,
        token = token,
        allowCleartext = allowCleartext,
        http = HttpClient(
            MockEngine { request ->
                sent += request.url.toString()
                authorizations += request.headers[HttpHeaders.Authorization]
                respond(body, headers = headersOf(HttpHeaders.ContentType, type))
            },
        ),
    )

    @Test
    fun aPageInsteadOfTheApiIsAnUnexpectedResponseNotACrash() = runTest {
        val api = client("<!doctype html><title>Accueil</title>", type = "text/html")

        assertFailsWith<ApiException.InvalidResponse> { api.health() }
        assertFailsWith<ApiException.InvalidResponse> { api.export() }
    }

    @Test
    fun releaseBuildsSendNothingInCleartext() = runTest {
        val api = client("""{"status":"ok"}""", baseUrl = { "http://192.168.1.10:8080" }, token = { "jeton" })

        val error = assertFailsWith<ApiException.InvalidAddress> { api.health() }
        assertTrue(error.cleartext)
        assertEquals(emptyList(), sent)

        // Vers le téléphone lui-même (adb reverse), HTTP reste permis.
        client("""{"status":"ok"}""", baseUrl = { "http://127.0.0.1:8080" }).health()
        assertEquals(listOf("http://127.0.0.1:8080/api/health"), sent)
    }

    @Test
    fun debugBuildsMayUseHttpOnTheLocalNetwork() = runTest {
        client("""{"status":"ok"}""", allowCleartext = true, baseUrl = { "http://192.168.1.10:8080" }).health()
        assertEquals(listOf("http://192.168.1.10:8080/api/health"), sent)
    }

    @Test
    fun aMalformedAddressIsReportedAsSuch() = runTest {
        val error = assertFailsWith<ApiException.InvalidAddress> { client("{}", baseUrl = { "stock.example" }).health() }
        assertTrue(!error.cleartext)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun theTokenIsAskedForTheServerBeingCalled() = runTest {
        var url = "https://stock.example/"
        val api = client("""{"status":"ok"}""", baseUrl = { url }, token = { if (it == "https://stock.example") "jeton" else null })

        api.health()
        url = "https://autre.example"
        api.health()

        assertEquals(listOf<String?>("Bearer jeton", null), authorizations)
    }

    @Test
    fun aRecordedMovementComesBackAsTheServerCountedIt() = runTest {
        val api = client(
            """{"movement":{"id":1,"kind":"out","quantity":2,"dye_lot":"81784","recipient":"Marie"},
               "lot":{"lot_id":1,"ean":"8435407948594","dye_lot":"81784","quantity":1},"replayed":true}""",
        )
        val response = api.recordMovement(MovementRequest("r1", "out", "8435407948594", "81784", 2, "Marie"))

        assertEquals(Movement(2, "81784", "Marie"), response.movement)
        assertTrue(response.replayed)
    }
}
