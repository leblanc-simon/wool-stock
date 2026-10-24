package eu.leblancsimon.woolstock.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Échec d'un appel : serveur injoignable, ou réponse en erreur. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : ApiException(cause.message ?: "network error", cause)

    class Server(val status: Int, val error: ApiError?) : ApiException(error?.message ?: "HTTP $status")
}

/**
 * Client de l'API du serveur de stock. Quelques appels JSON : HttpURLConnection suffit.
 * [baseUrl] et [token] sont relus à chaque appel, pour suivre réglages et connexion ;
 * [onUnauthorized] est appelé quand le serveur ne reconnaît plus la session.
 */
class ApiClient(
    private val baseUrl: () -> String,
    private val token: () -> String? = { null },
    private val onUnauthorized: () -> Unit = {},
) {
    suspend fun health(): HealthResponse = get("/api/health")

    /** Ouvre une session pour une adresse autorisée ; 403 error.email_not_allowed sinon. */
    suspend fun login(email: String, device: String): Session =
        send("POST", "/api/auth/login", json.encodeToString(LoginRequest(email, device)))

    suspend fun me(): User = get<MeResponse>("/api/auth/me").user

    suspend fun logout() {
        execute("POST", "/api/auth/logout", "{}")
    }

    suspend fun users(): List<User> = get<UsersResponse>("/api/users").users

    suspend fun createUser(email: String, name: String, role: UserRole): User =
        send("POST", "/api/users", json.encodeToString(NewUserRequest(email, name, role.apiValue)))

    suspend fun references(): List<RemoteReference> = get<ReferencesResponse>("/api/references").references

    /** null si le serveur ne connaît pas cet EAN. */
    suspend fun reference(ean: String): RemoteReference? = try {
        get("/api/references/${ean.encoded()}")
    } catch (e: ApiException.Server) {
        if (e.status == HttpURLConnection.HTTP_NOT_FOUND) null else throw e
    }

    suspend fun saveReference(ean: String, fields: ReferenceFields): RemoteReference =
        send("PUT", "/api/references/${ean.encoded()}", json.encodeToString(fields))

    /** Bains en stock, d'une seule référence si [ean] est donné ; [includeEmpty] garde les bains épuisés. */
    suspend fun stock(ean: String? = null, includeEmpty: Boolean = false): List<StockLot> {
        val query = listOfNotNull(ean?.let { "ean=${it.encoded()}" }, "include_empty=true".takeIf { includeEmpty })
        return get<StockResponse>("/api/stock" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")).items
    }

    suspend fun recordMovement(request: MovementRequest): MovementResponse =
        send("POST", "/api/movements", json.encodeToString(request))

    /** Inventaire en cours, partagé par tous les téléphones ; null s'il n'y en a pas. */
    suspend fun currentInventory(): InventoryState? = try {
        get("/api/inventories/current")
    } catch (e: ApiException.Server) {
        if (e.status == HttpURLConnection.HTTP_NOT_FOUND) null else throw e
    }

    suspend fun startInventory(scope: InventoryScope): InventoryState =
        send("POST", "/api/inventories", json.encodeToString(StartInventoryBody(scope.apiValue)))

    suspend fun closeInventory(id: Long): InventoryState = send("POST", "/api/inventories/$id/close", "{}")

    suspend fun count(inventoryId: Long, request: CountRequest): CountResponse =
        send("POST", "/api/inventories/$inventoryId/counts", json.encodeToString(request))

    private suspend inline fun <reified T> get(path: String): T = json.decodeFromString(execute("GET", path, null))

    private suspend inline fun <reified T> send(method: String, path: String, body: String): T =
        json.decodeFromString(execute(method, path, body))

    private suspend fun execute(method: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        try {
            val connection = URL(baseUrl().trim().trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                // Le serveur traduit ses messages d'erreur dans la langue du téléphone.
                connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                token()?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = connection.responseCode
                val success = status in 200..299
                val text = (if (success) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (!success) {
                    // Session inconnue du serveur (compte supprimé, base remise à zéro) : on se déconnecte.
                    if (status == HttpURLConnection.HTTP_UNAUTHORIZED) onUnauthorized()
                    throw ApiException.Server(status, runCatching { json.decodeFromString<ApiErrorBody>(text).error }.getOrNull())
                }
                text
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            throw ApiException.Network(e)
        } catch (e: IllegalArgumentException) {
            // Adresse du serveur mal formée dans les réglages.
            throw ApiException.Network(e)
        }
    }

    private fun String.encoded() = URLEncoder.encode(this, Charsets.UTF_8.name())

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
