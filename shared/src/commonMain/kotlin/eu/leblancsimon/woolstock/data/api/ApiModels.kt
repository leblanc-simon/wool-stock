package eu.leblancsimon.woolstock.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Entrée ou sortie de stock, sous la valeur attendue par l'API. */
enum class MovementKind(val apiValue: String) {
    IN("in"),
    OUT("out"),
}

/** Référence du cache serveur : ce que le code-barre permet de préremplir. */
@Serializable
data class RemoteReference(
    val ean: String,
    val brand: String? = null,
    @SerialName("color_code") val colorCode: String? = null,
    @SerialName("color_name") val colorName: String? = null,
)

@Serializable
data class ReferenceFields(
    val brand: String?,
    @SerialName("color_code") val colorCode: String?,
    @SerialName("color_name") val colorName: String?,
)

/** Un bain d'une référence et sa quantité dans le stock global. */
@Serializable
data class StockLot(
    @SerialName("lot_id") val lotId: Long,
    val ean: String,
    /** Chaîne vide pour une laine sans numéro de bain. */
    @SerialName("dye_lot") val dyeLot: String,
    val quantity: Int,
    val brand: String? = null,
    @SerialName("color_code") val colorCode: String? = null,
    @SerialName("color_name") val colorName: String? = null,
)

@Serializable
data class MovementRequest(
    /** Identifiant de la saisie : un renvoi après coupure réseau n'est pas compté deux fois. */
    @SerialName("request_id") val requestId: String,
    val kind: String,
    val ean: String,
    @SerialName("dye_lot") val dyeLot: String,
    val quantity: Int,
    val recipient: String? = null,
    val reference: ReferenceFields? = null,
)

/** Le mouvement enregistré par le serveur, tel qu'il l'a compté. */
@Serializable
data class Movement(
    val quantity: Int,
    @SerialName("dye_lot") val dyeLot: String,
    val recipient: String? = null,
)

@Serializable
data class MovementResponse(val movement: Movement, val lot: StockLot, val replayed: Boolean = false)

@Serializable
data class HealthResponse(val status: String, val version: String = "")

@Serializable
internal data class ReferencesResponse(val references: List<RemoteReference>)

@Serializable
internal data class StockResponse(val items: List<StockLot>)

@Serializable
internal data class ApiErrorBody(val error: ApiError)

/** Erreur renvoyée par le serveur : code stable (clé de traduction) et message déjà traduit. */
@Serializable
data class ApiError(
    val code: String,
    val message: String = "",
    val field: String? = null,
    val available: Int? = null,
    /** Inventaire : quantité déjà comptée pour ce bain (error.already_counted). */
    val counted: Int? = null,
)

/** Comment un comptage se combine à un comptage déjà fait du même bain. */
enum class CountMode(val apiValue: String) {
    /** Premier comptage ; refusé par le serveur si le bain est déjà compté. */
    NEW("new"),

    /** Ajouté au comptage déjà fait : pelotes trouvées ailleurs. */
    ADD("add"),

    /** Remplace le comptage déjà fait : bain recompté. */
    SET("set"),
}

/** Inventaire partiel (seuls les bains comptés changent) ou complet (les autres sont remis à 0 à la clôture). */
enum class InventoryScope(val apiValue: String) {
    PARTIAL("partial"),
    FULL("full"),
}

@Serializable
data class Inventory(
    val id: Long,
    val scope: String = InventoryScope.PARTIAL.apiValue,
    @SerialName("started_at") val startedAt: String,
    @SerialName("started_by") val startedBy: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
) {
    val isFull: Boolean get() = scope == InventoryScope.FULL.apiValue
}

/** Quantité comptée d'un bain pendant l'inventaire. */
@Serializable
data class InventoryCount(
    @SerialName("lot_id") val lotId: Long,
    val ean: String,
    @SerialName("dye_lot") val dyeLot: String,
    val brand: String? = null,
    @SerialName("color_code") val colorCode: String? = null,
    @SerialName("color_name") val colorName: String? = null,
    val quantity: Int,
    /** Stock du bain avant son premier comptage. */
    @SerialName("previous_quantity") val previousQuantity: Int,
    /** Bain non compté d'un inventaire complet, remis à 0 à la clôture. */
    val zeroed: Boolean = false,
)

/**
 * Un inventaire et ses bains comptés, du plus récent au plus ancien. [uncounted] est le
 * nombre de bains en stock pas encore comptés : ceux qu'un inventaire complet remettra à 0.
 */
@Serializable
data class InventoryState(val inventory: Inventory, val counts: List<InventoryCount>, val uncounted: Int = 0)

@Serializable
data class CountRequest(
    @SerialName("request_id") val requestId: String,
    val ean: String,
    @SerialName("dye_lot") val dyeLot: String,
    val quantity: Int,
    val mode: String,
    val reference: ReferenceFields? = null,
)

@Serializable
data class CountResponse(val count: InventoryCount, val lot: StockLot, val replayed: Boolean = false)

@Serializable
internal data class StartInventoryBody(val scope: String)

/** Droit d'un compte : l'admin peut, en plus, créer des comptes. */
enum class UserRole(val apiValue: String) {
    ADMIN("admin"),
    MANAGER("manager"),
}

@Serializable
data class User(
    val id: Long,
    val email: String,
    val name: String,
    val role: String,
) {
    val isAdmin: Boolean get() = role == UserRole.ADMIN.apiValue
}

/** Connexion gardée sur le téléphone : le jeton vaut tant qu'il sert (le serveur le fait expirer après 400 jours sans usage). */
@Serializable
data class Session(val token: String, val user: User)

@Serializable
internal data class LoginRequest(val email: String, val device: String)

@Serializable
internal data class MeResponse(val user: User)

@Serializable
internal data class UsersResponse(val users: List<User>)

@Serializable
internal data class NewUserRequest(val email: String, val name: String, val role: String)

/** Classeur Excel téléchargé (GET /api/export) : le nom proposé par le serveur et son contenu. */
class ExportFile(val name: String, val bytes: ByteArray) {
    companion object {
        const val TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        /** Nom de secours si le serveur n'en propose pas. */
        const val DEFAULT_NAME = "wool-stock.xlsx"
    }
}
