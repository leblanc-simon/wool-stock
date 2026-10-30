package io.leblanc.woolstock.data.api

import io.leblanc.woolstock.platform.languageTag
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLParserException
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.http.parseUrl
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Échec d'un appel : serveur injoignable, adresse inutilisable, réponse illisible ou en erreur. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : ApiException(cause.message ?: "network error", cause)

    /**
     * Adresse du serveur inutilisable, la requête n'est pas partie : mal formée, ou en HTTP
     * ([cleartext]) alors que cette version de l'app exige HTTPS.
     */
    class InvalidAddress(val cleartext: Boolean, cause: Throwable? = null) :
        ApiException(if (cleartext) "https required" else "invalid server address", cause)

    /** Réponse réussie mais illisible : l'adresse mène sans doute à un autre site que le serveur. */
    class InvalidResponse(cause: Throwable? = null) : ApiException("unexpected response", cause)

    class Server(val status: Int, val error: ApiError?) : ApiException(error?.message ?: "HTTP $status")
}

/**
 * Client de l'API du serveur de stock, sur Ktor (moteur OkHttp ou Darwin selon la plateforme).
 * [baseUrl] est relue à chaque appel, pour suivre les réglages ; [token] donne le jeton de
 * session à présenter à ce serveur-là (aucun pour un autre), et [onUnauthorized] est appelé
 * quand le serveur ne reconnaît plus la session. Sans [allowCleartext] (versions publiées),
 * seul HTTPS est permis, sauf vers le téléphone lui-même. [userAgent] nomme l'app auprès du
 * serveur (« io.leblanc.woolstock v0.8.0 »).
 */
class ApiClient(
    private val baseUrl: () -> String,
    private val token: (serverUrl: String) -> String? = { null },
    private val onUnauthorized: () -> Unit = {},
    private val allowCleartext: Boolean = false,
    userAgent: String? = null,
    private val http: HttpClient = HttpClient { woolStockDefaults(userAgent) },
) {
    /** Le même client vers une autre adresse, sans session : pour tester un serveur avant de l'enregistrer. */
    fun at(url: String): ApiClient = ApiClient(baseUrl = { url }, allowCleartext = allowCleartext, http = http)

    suspend fun health(): HealthResponse = get("/api/health")

    /** Ouvre une session pour une adresse autorisée ; 403 error.email_not_allowed sinon. */
    suspend fun login(email: String, device: String): Session =
        send(HttpMethod.Post, "/api/auth/login", json.encodeToString(LoginRequest(email, device)))

    suspend fun me(): User = get<MeResponse>("/api/auth/me").user

    suspend fun logout() {
        execute(HttpMethod.Post, "/api/auth/logout", "{}")
    }

    /** Supprime le compte connecté et toutes ses sessions ; 409 error.last_admin pour le dernier admin. */
    suspend fun deleteAccount() {
        execute(HttpMethod.Delete, "/api/auth/me", null)
    }

    suspend fun users(): List<User> = get<UsersResponse>("/api/users").users

    suspend fun createUser(email: String, name: String, role: UserRole): User =
        send(HttpMethod.Post, "/api/users", json.encodeToString(NewUserRequest(email, name, role.apiValue)))

    suspend fun references(): List<RemoteReference> = get<ReferencesResponse>("/api/references").references

    /** null si le serveur ne connaît pas cet EAN. */
    suspend fun reference(ean: String): RemoteReference? = try {
        get("/api/references/${ean.encodeURLPathPart()}")
    } catch (e: ApiException.Server) {
        if (e.status == HttpStatusCode.NotFound.value) null else throw e
    }

    suspend fun saveReference(ean: String, fields: ReferenceFields): RemoteReference =
        send(HttpMethod.Put, "/api/references/${ean.encodeURLPathPart()}", json.encodeToString(fields))

    /** Bains en stock, d'une seule référence si [ean] est donné ; [includeEmpty] garde les bains épuisés. */
    suspend fun stock(ean: String? = null, includeEmpty: Boolean = false): List<StockLot> {
        val query = listOfNotNull(ean?.let { "ean=${it.encodeURLPathPart()}" }, "include_empty=true".takeIf { includeEmpty })
        return get<StockResponse>("/api/stock" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")).items
    }

    suspend fun recordMovement(request: MovementRequest): MovementResponse =
        send(HttpMethod.Post, "/api/movements", json.encodeToString(request))

    /** Inventaire en cours, partagé par tous les téléphones ; null s'il n'y en a pas. */
    suspend fun currentInventory(): InventoryState? = try {
        get("/api/inventories/current")
    } catch (e: ApiException.Server) {
        if (e.status == HttpStatusCode.NotFound.value) null else throw e
    }

    suspend fun startInventory(scope: InventoryScope): InventoryState =
        send(HttpMethod.Post, "/api/inventories", json.encodeToString(StartInventoryBody(scope.apiValue)))

    suspend fun closeInventory(id: Long): InventoryState = send(HttpMethod.Post, "/api/inventories/$id/close", "{}")

    suspend fun count(inventoryId: Long, request: CountRequest): CountResponse =
        send(HttpMethod.Post, "/api/inventories/$inventoryId/counts", json.encodeToString(request))

    /** Classeur Excel du stock et des distributions, titré dans la langue du téléphone. */
    suspend fun export(): ExportFile = request(HttpMethod.Get, "/api/export", null, accept = ExportFile.TYPE) { response ->
        // Un autre site répondrait 200 avec une page : ce ne serait pas un classeur.
        if (response.headers[HttpHeaders.ContentType]?.startsWith(ExportFile.TYPE) != true) throw ApiException.InvalidResponse()
        ExportFile(
            name = attachmentName(response.headers[HttpHeaders.ContentDisposition]) ?: ExportFile.DEFAULT_NAME,
            bytes = response.bodyAsBytes(),
        )
    }

    private suspend inline fun <reified T> get(path: String): T = decode(execute(HttpMethod.Get, path, null))

    private suspend inline fun <reified T> send(method: HttpMethod, path: String, body: String): T =
        decode(execute(method, path, body))

    /** Lit une réponse JSON ; illisible, ce n'est sans doute pas le serveur de stock qui répond. */
    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString(text)
    } catch (e: SerializationException) {
        throw ApiException.InvalidResponse(e)
    }

    private suspend fun execute(method: HttpMethod, path: String, body: String?): String =
        request(method, path, body, accept = "application/json") { it.bodyAsText() }

    /**
     * Envoie une requête et lit une réponse réussie avec [read] ; une erreur reste au format JSON
     * de l'API. L'adresse est vérifiée avant l'envoi : le jeton ne part ni en clair (hors
     * développement) ni vers une adresse mal formée.
     */
    private suspend fun <T> request(
        method: HttpMethod,
        path: String,
        body: String?,
        accept: String,
        read: suspend (HttpResponse) -> T,
    ): T {
        val serverUrl = baseUrl().trim().trimEnd('/')
        val url = parseUrl(serverUrl + path)?.takeIf { isHttpUrl(serverUrl) }
            ?: throw ApiException.InvalidAddress(cleartext = false)
        if (!cleartextAllowed(url, allowCleartext)) throw ApiException.InvalidAddress(cleartext = true)
        val response = try {
            http.request(url) {
                this.method = method
                header(HttpHeaders.Accept, accept)
                // Le serveur traduit ses messages d'erreur (et l'export) dans la langue du téléphone.
                header(HttpHeaders.AcceptLanguage, languageTag())
                token(serverUrl)?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (body != null) setBody(TextContent(body, ContentType.Application.Json.withCharset(Charsets.UTF_8)))
            }
        } catch (e: IOException) {
            throw ApiException.Network(e)
        } catch (e: URLParserException) {
            throw ApiException.InvalidAddress(cleartext = false, cause = e)
        } catch (e: IllegalArgumentException) {
            // Adresse lue par Ktor mais refusée par le moteur HTTP.
            throw ApiException.InvalidAddress(cleartext = false, cause = e)
        }
        try {
            val status = response.status.value
            if (status !in 200..299) {
                val text = runCatching { response.bodyAsText() }.getOrDefault("")
                // Session inconnue du serveur (compte supprimé, base remise à zéro) : on se déconnecte.
                if (status == HttpStatusCode.Unauthorized.value) onUnauthorized()
                throw ApiException.Server(status, runCatching { json.decodeFromString<ApiErrorBody>(text).error }.getOrNull())
            }
            return read(response)
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 10_000L

        private val ENCODED_FILENAME = Regex("""filename\*=UTF-8''([^;\s]+)""", RegexOption.IGNORE_CASE)
        private val PLAIN_FILENAME = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE)

        /** Délais et User-Agent de tous les appels ; sans [userAgent], celui du moteur. */
        internal fun HttpClientConfig<*>.woolStockDefaults(userAgent: String?) {
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = READ_TIMEOUT_MS
            }
            if (userAgent != null) install(UserAgent) { agent = userAgent }
        }

        /**
         * Nom de fichier d'un en-tête Content-Disposition (« attachment; filename=stock.xlsx », ou
         * sa forme encodée filename*=UTF-8''…), sans chemin ; null s'il n'y en a pas.
         */
        internal fun attachmentName(header: String?): String? {
            if (header == null) return null
            val encoded = ENCODED_FILENAME.find(header)?.groupValues?.get(1)
                ?.let { runCatching { it.decodeURLPart() }.getOrNull() }
            val name = (encoded ?: PLAIN_FILENAME.find(header)?.groupValues?.get(1))
                ?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()
            return name?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        }

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
