package io.leblanc.woolstock.data

import io.leblanc.woolstock.data.api.ApiClient
import io.leblanc.woolstock.data.api.ApiException
import io.leblanc.woolstock.data.api.CountMode
import io.leblanc.woolstock.data.api.CountRequest
import io.leblanc.woolstock.data.api.CountResponse
import io.leblanc.woolstock.data.api.ExportFile
import io.leblanc.woolstock.data.api.HealthResponse
import io.leblanc.woolstock.data.api.InventoryScope
import io.leblanc.woolstock.data.api.InventoryState
import io.leblanc.woolstock.data.api.MovementKind
import io.leblanc.woolstock.data.api.MovementRequest
import io.leblanc.woolstock.data.api.MovementResponse
import io.leblanc.woolstock.data.api.ReferenceFields
import io.leblanc.woolstock.data.api.RemoteReference
import io.leblanc.woolstock.data.api.Session
import io.leblanc.woolstock.data.api.StockLot
import io.leblanc.woolstock.data.api.User
import io.leblanc.woolstock.data.api.UserRole
import io.leblanc.woolstock.platform.deviceName
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

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
        val session = api.login(email.trim(), device = deviceName())
        settings.saveSession(session)
        return session
    }

    /**
     * Se déconnecte, même si le serveur est injoignable : la session et la copie du cache sont
     * oubliées. La session l'est en dernier : sa disparition ramène à l'écran de connexion.
     */
    suspend fun logout() {
        try {
            api.logout()
        } catch (_: ApiException) {
        }
        dao.clearReferences()
        settings.clearSession()
    }

    /** Supprime le compte sur le serveur, puis oublie le cache et la session ; en cas d'échec, rien ne change ici. */
    suspend fun deleteAccount() {
        api.deleteAccount()
        dao.clearReferences()
        settings.clearSession()
    }

    /**
     * Change d'adresse de serveur. La session et la copie du cache appartiennent à l'ancien
     * serveur : la session y est fermée s'il répond vite, puis toutes deux sont oubliées, pour
     * que ni le jeton ni les références ne passent d'un serveur à l'autre.
     */
    suspend fun changeServer(url: String) {
        if (!settings.isCurrentServer(url)) {
            if (settings.session.value != null) {
                withTimeoutOrNull(LOGOUT_TIMEOUT_MS) {
                    try {
                        api.logout()
                    } catch (_: ApiException) {
                    }
                }
            }
            dao.clearReferences()
        }
        settings.saveServerUrl(url)
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

    /** Classeur Excel du stock et des distributions, le même que l'export des pages web. */
    suspend fun export(): ExportFile = api.export()

    /** Bains d'une référence, pour choisir celui d'une saisie ; [includeEmpty] garde les bains épuisés. */
    suspend fun lots(ean: String, includeEmpty: Boolean = false): List<StockLot> = api.stock(ean, includeEmpty)

    /**
     * Envoie le mouvement et renvoie le mouvement enregistré et le bain mis à jour. [requestId]
     * reste le même pour tous les essais d'une saisie : un renvoi après coupure réseau ne compte
     * pas deux fois.
     */
    suspend fun record(input: MovementInput, requestId: String): MovementResponse {
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
        return response
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
    suspend fun checkServer(url: String): HealthResponse = api.at(url).health()

    private suspend fun cache(remote: RemoteReference): YarnReference {
        val entity = remote.toEntity(dao.reference(remote.ean)?.createdAt)
        dao.upsertReference(entity)
        return entity
    }

    private fun RemoteReference.toEntity(createdAt: Long?) =
        YarnReference(ean, brand, colorCode, colorName, createdAt ?: Clock.System.now().toEpochMilliseconds())

    private companion object {
        /** Fermer la session sur l'ancien serveur ne retient pas plus longtemps le changement d'adresse. */
        const val LOGOUT_TIMEOUT_MS = 3_000L
    }
}
