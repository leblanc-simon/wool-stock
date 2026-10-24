package eu.leblancsimon.woolstock.data

import android.os.Build
import eu.leblancsimon.woolstock.data.api.ApiClient
import eu.leblancsimon.woolstock.data.api.ApiException
import eu.leblancsimon.woolstock.data.api.CountMode
import eu.leblancsimon.woolstock.data.api.CountRequest
import eu.leblancsimon.woolstock.data.api.CountResponse
import eu.leblancsimon.woolstock.data.api.HealthResponse
import eu.leblancsimon.woolstock.data.api.InventoryScope
import eu.leblancsimon.woolstock.data.api.InventoryState
import eu.leblancsimon.woolstock.data.api.MovementKind
import eu.leblancsimon.woolstock.data.api.MovementRequest
import eu.leblancsimon.woolstock.data.api.ReferenceFields
import eu.leblancsimon.woolstock.data.api.RemoteReference
import eu.leblancsimon.woolstock.data.api.Session
import eu.leblancsimon.woolstock.data.api.StockLot
import eu.leblancsimon.woolstock.data.api.User
import eu.leblancsimon.woolstock.data.api.UserRole

/** Une entrée ou une sortie saisie sur le téléphone. */
data class MovementInput(
    val kind: MovementKind,
    val ean: String,
    val dyeLot: String,
    val quantity: Int,
    /** Sortie : la personne pour qui les pelotes sortent. */
    val recipient: String? = null,
    /** Entrée : marque et coloris validés, qui mettent à jour le cache. */
    val reference: ReferenceFields? = null,
)

/** Un bain compté pendant l'inventaire, avec marque et coloris validés (qui mettent à jour le cache). */
data class CountInput(
    val ean: String,
    val dyeLot: String,
    val quantity: Int,
    val reference: ReferenceFields?,
)

/**
 * Le stock global vit sur le serveur ; le téléphone garde une copie du cache des
 * références pour préremplir marque et coloris sans attendre le réseau.
 */
/** De quoi proposer marques et noms de coloris pendant la saisie. */
data class SuggestionSources(val brands: List<String>, val colorNames: List<ColorNameUse>)

class StockRepository(
    database: WoolStockDatabase,
    private val api: ApiClient,
    private val settings: AppSettings,
    /** Marques du dictionnaire embarqué, à l'orthographe de référence. */
    private val dictionaryBrands: () -> List<String>,
) {
    private val dao = database.yarnDao()

    /** Connexion par adresse seule ; la session est gardée sur le téléphone. */
    suspend fun login(email: String): Session {
        val session = api.login(email.trim(), device = "${Build.MANUFACTURER} ${Build.MODEL}")
        settings.saveSession(session)
        return session
    }

    /** Se déconnecte, même si le serveur est injoignable : la session locale est oubliée. */
    suspend fun logout() {
        try {
            api.logout()
        } catch (_: ApiException) {
        }
        settings.clearSession()
    }

    suspend fun users(): List<User> = api.users()

    suspend fun createUser(email: String, name: String, role: UserRole): User = api.createUser(email.trim(), name.trim(), role)

    suspend fun findLocalReference(ean: String): YarnReference? = dao.reference(ean)

    /**
     * Marques : le dictionnaire d'abord (orthographe de référence), puis celles déjà saisies.
     * Le cache local étant recopié du serveur, on retrouve aussi les saisies des autres téléphones.
     */
    suspend fun suggestionSources(): SuggestionSources =
        SuggestionSources(brands = dictionaryBrands() + dao.brands(), colorNames = dao.colorNames())

    /** Demande au serveur une référence absente du cache local, et la garde. */
    suspend fun fetchReference(ean: String): YarnReference? = api.reference(ean)?.let { cache(it) }

    /**
     * Recopie le cache du serveur. Les références enregistrées avant l'arrivée du serveur
     * (stock local de la première version) lui sont transmises une fois.
     */
    suspend fun syncReferences() {
        val remote = api.references()
        val known = remote.mapTo(HashSet()) { it.ean }
        val local = dao.references()
        local.filter { it.ean !in known }.forEach {
            api.saveReference(it.ean, ReferenceFields(it.brand, it.colorCode, it.colorName))
        }
        val createdAt = local.associate { it.ean to it.createdAt }
        dao.upsertReferences(remote.map { it.toEntity(createdAt[it.ean]) })
    }

    suspend fun stock(): List<StockLot> = api.stock()

    /** Bains d'une référence, pour choisir celui d'une saisie ; [includeEmpty] garde les bains épuisés. */
    suspend fun lots(ean: String, includeEmpty: Boolean = false): List<StockLot> = api.stock(ean, includeEmpty)

    /**
     * Envoie le mouvement et renvoie le bain mis à jour. [requestId] reste le même pour
     * tous les essais d'une saisie : un renvoi après coupure réseau ne compte pas deux fois.
     */
    suspend fun record(input: MovementInput, requestId: String): StockLot {
        val response = api.recordMovement(
            MovementRequest(
                requestId = requestId,
                kind = input.kind.apiValue,
                ean = input.ean,
                dyeLot = input.dyeLot,
                quantity = input.quantity,
                recipient = input.recipient,
                reference = input.reference,
            ),
        )
        val lot = response.lot
        if (input.kind == MovementKind.IN) cache(RemoteReference(lot.ean, lot.brand, lot.colorCode, lot.colorName))
        return lot
    }

    suspend fun currentInventory(): InventoryState? = api.currentInventory()

    suspend fun startInventory(scope: InventoryScope): InventoryState = api.startInventory(scope)

    /** Termine l'inventaire ; un inventaire complet remet à 0 les bains non comptés. */
    suspend fun closeInventory(id: Long): InventoryState = api.closeInventory(id)

    /**
     * Enregistre le comptage d'un bain ; le stock prend la quantité comptée. Un bain déjà
     * compté dans l'inventaire est refusé (error.already_counted) tant que [mode] vaut NEW.
     */
    suspend fun count(inventoryId: Long, input: CountInput, mode: CountMode, requestId: String): CountResponse {
        val response = api.count(
            inventoryId,
            CountRequest(
                requestId = requestId,
                ean = input.ean,
                dyeLot = input.dyeLot,
                quantity = input.quantity,
                mode = mode.apiValue,
                reference = input.reference,
            ),
        )
        val lot = response.lot
        cache(RemoteReference(lot.ean, lot.brand, lot.colorCode, lot.colorName))
        return response
    }

    /** Vérifie qu'un serveur répond à cette adresse, avant de l'enregistrer. */
    suspend fun checkServer(url: String): HealthResponse = ApiClient(baseUrl = { url }).health()

    private suspend fun cache(remote: RemoteReference): YarnReference {
        val entity = remote.toEntity(dao.reference(remote.ean)?.createdAt)
        dao.upsertReference(entity)
        return entity
    }

    private fun RemoteReference.toEntity(createdAt: Long?) =
        YarnReference(ean, brand, colorCode, colorName, createdAt ?: System.currentTimeMillis())
}
