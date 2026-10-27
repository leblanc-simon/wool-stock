package eu.leblancsimon.woolstock.confirm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import eu.leblancsimon.woolstock.data.CountInput
import eu.leblancsimon.woolstock.data.MovementInput
import eu.leblancsimon.woolstock.data.StockRepository
import eu.leblancsimon.woolstock.data.SuggestionSources
import eu.leblancsimon.woolstock.data.YarnReference
import eu.leblancsimon.woolstock.data.api.ApiException
import eu.leblancsimon.woolstock.data.api.CountMode
import eu.leblancsimon.woolstock.data.api.MovementKind
import eu.leblancsimon.woolstock.data.api.ReferenceFields
import eu.leblancsimon.woolstock.data.api.StockLot
import eu.leblancsimon.woolstock.parser.Gtin
import eu.leblancsimon.woolstock.parser.Suggestions
import eu.leblancsimon.woolstock.resources.Res
import eu.leblancsimon.woolstock.resources.error_insufficient_stock
import eu.leblancsimon.woolstock.resources.source_barcode
import eu.leblancsimon.woolstock.resources.source_cache
import eu.leblancsimon.woolstock.resources.source_empty
import eu.leblancsimon.woolstock.resources.source_manual
import eu.leblancsimon.woolstock.resources.source_ocr
import eu.leblancsimon.woolstock.resources.source_stock
import eu.leblancsimon.woolstock.resources.validation_count
import eu.leblancsimon.woolstock.resources.validation_ean_format
import eu.leblancsimon.woolstock.resources.validation_ean_required
import eu.leblancsimon.woolstock.resources.validation_quantity
import eu.leblancsimon.woolstock.resources.validation_recipient_required
import eu.leblancsimon.woolstock.ui.ConfirmRoute
import eu.leblancsimon.woolstock.ui.ErrorMessage
import eu.leblancsimon.woolstock.ui.SavedScan
import eu.leblancsimon.woolstock.ui.ScanMode
import eu.leblancsimon.woolstock.ui.toErrorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

enum class FieldSource(val label: StringResource) {
    BARCODE(Res.string.source_barcode),
    CACHE(Res.string.source_cache),
    OCR(Res.string.source_ocr),
    STOCK(Res.string.source_stock),
    MANUAL(Res.string.source_manual),
    EMPTY(Res.string.source_empty),
}

data class FieldState(
    val value: String = "",
    val source: FieldSource = FieldSource.EMPTY,
    /** Lecture OCR brute, quand la correction O→0, I→1… l'a modifiée. */
    val raw: String? = null,
)

enum class ConfirmField { EAN, BRAND, COLOR_CODE, COLOR_NAME, DYE_LOT }

/** Bain déjà compté dans l'inventaire : il faut choisir entre ajouter et remplacer. */
data class DuplicateCount(val counted: Int, val quantity: Int)

data class ConfirmUiState(
    val mode: ScanMode,
    val ean: FieldState,
    val brand: FieldState,
    val colorCode: FieldState,
    val colorName: FieldState,
    val dyeLot: FieldState,
    /** Un comptage part d'un champ vide : la quantité se saisit, elle ne se devine pas. */
    val quantity: String = if (mode == ScanMode.INVENTORY) "" else "1",
    /** Sortie : la personne pour qui les pelotes sortent. */
    val recipient: String = "",
    /** Sortie : bains en stock de la référence ; inventaire : tous ses bains. null tant qu'ils ne sont pas connus. */
    val lots: List<StockLot>? = null,
    val eanError: ErrorMessage? = null,
    val quantityError: ErrorMessage? = null,
    val recipientError: ErrorMessage? = null,
    /** Échec qui ne concerne aucun champ : serveur injoignable, erreur serveur. */
    val error: ErrorMessage? = null,
    val duplicate: DuplicateCount? = null,
    /** Orthographes déjà connues, proposées pendant la saisie de la marque et du nom de coloris. */
    val brandSuggestions: List<String> = emptyList(),
    val colorNameSuggestions: List<String> = emptyList(),
    val knownReference: Boolean = false,
    val saving: Boolean = false,
) {
    /** Bain du stock qui correspond au bain saisi. */
    val selectedLot: StockLot? get() = lots?.firstOrNull { it.dyeLot == dyeLot.value.trim() }

    /** Marque et coloris se corrigent à l'entrée et au comptage ; une sortie ne touche pas au cache. */
    val referenceEditable: Boolean get() = mode != ScanMode.EXIT
}

class ConfirmViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: StockRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ConfirmRoute>()

    /**
     * Identifiant d'envoi, le même pour tous les essais d'une même saisie (un renvoi n'est jamais
     * compté deux fois), un autre dès que la saisie est corrigée ; gardé si l'écran est recréé.
     */
    private val requestIds = RequestIds(
        content = savedStateHandle.get<String>(KEY_REQUEST_CONTENT),
        id = savedStateHandle.get<String>(KEY_REQUEST_ID),
    )

    private val _state = MutableStateFlow(
        ConfirmUiState(
            mode = route.mode,
            ean = field(route.ean, if (route.fromStock) FieldSource.STOCK else FieldSource.BARCODE),
            brand = field(route.brand, if (route.fromStock) FieldSource.STOCK else FieldSource.OCR),
            colorCode = field(route.colorCode, if (route.fromStock) FieldSource.STOCK else FieldSource.OCR),
            colorName = field(route.colorName, if (route.fromStock) FieldSource.STOCK else FieldSource.OCR),
            dyeLot = field(route.dyeLot, if (route.fromStock) FieldSource.STOCK else FieldSource.OCR)
                .copy(raw = route.dyeLotRaw?.takeIf { it != route.dyeLot }),
        ),
    )
    val state: StateFlow<ConfirmUiState> = _state.asStateFlow()

    private val _saved = Channel<SavedScan>(Channel.CONFLATED)
    val saved: Flow<SavedScan> = _saved.receiveAsFlow()

    private var lookupJob: Job? = null
    private var sources = SuggestionSources(emptyList(), emptyList())

    init {
        route.ean?.let(::lookup)
        viewModelScope.launch {
            sources = repository.suggestionSources()
            refreshSuggestions()
        }
    }

    fun onChange(field: ConfirmField, value: String) {
        val edited = FieldState(value, if (value.isBlank()) FieldSource.EMPTY else FieldSource.MANUAL)
        _state.update {
            when (field) {
                ConfirmField.EAN -> it.copy(ean = edited, eanError = null)
                ConfirmField.BRAND -> it.copy(brand = edited)
                ConfirmField.COLOR_CODE -> it.copy(colorCode = edited)
                ConfirmField.COLOR_NAME -> it.copy(colorName = edited)
                ConfirmField.DYE_LOT -> it.copy(dyeLot = edited, quantityError = null)
            }.copy(error = null)
        }
        if (field == ConfirmField.EAN) lookup(value)
        refreshSuggestions()
    }

    /** Une suggestion choisie remplace la saisie : même orthographe pour tout le monde. */
    fun onSuggestion(field: ConfirmField, value: String) {
        val chosen = FieldState(value, FieldSource.MANUAL)
        _state.update {
            when (field) {
                ConfirmField.BRAND -> it.copy(brand = chosen)
                ConfirmField.COLOR_NAME -> it.copy(colorName = chosen)
                else -> it
            }
        }
        refreshSuggestions()
    }

    private fun refreshSuggestions() = _state.update {
        it.copy(
            brandSuggestions = Suggestions.suggest(it.brand.value, sources.brands),
            colorNameSuggestions = Suggestions.suggest(it.colorName.value, colorNameCandidates(it.brand.value)),
        )
    }

    /** Les noms de coloris déjà employés pour cette marque d'abord, puis les autres. */
    private fun colorNameCandidates(brand: String): List<String> {
        val (sameBrand, others) = sources.colorNames.partition { use ->
            brand.isNotBlank() && use.brand != null && Suggestions.sameValue(use.brand, brand)
        }
        return (sameBrand + others).map { it.colorName }
    }

    fun onQuantityChange(value: String) = _state.update {
        it.copy(quantity = value.filter(Char::isDigit), quantityError = null, error = null)
    }

    fun onRecipientChange(value: String) = _state.update { it.copy(recipient = value, recipientError = null, error = null) }

    fun onLotSelected(lot: StockLot) = _state.update {
        it.copy(dyeLot = FieldState(lot.dyeLot, FieldSource.STOCK), quantityError = null, error = null)
    }

    /**
     * Cache local d'abord (instantané, même sans réseau), puis serveur ; le cache est
     * prioritaire sur l'OCR mais ne remplace pas une saisie. En sortie et en inventaire,
     * charge aussi les bains en stock de la référence.
     */
    private fun lookup(rawEan: String) {
        lookupJob?.cancel()
        val ean = Gtin.normalize(rawEan)
        _state.update { it.copy(lots = null, knownReference = false) }
        if (ean == null) return
        lookupJob = viewModelScope.launch {
            val local = repository.findLocalReference(ean)
            local?.let(::applyReference)
            if (route.mode != ScanMode.ENTRY) loadLots(ean)
            if (local == null) {
                val remote = try {
                    repository.fetchReference(ean)
                } catch (_: ApiException) {
                    null
                }
                remote?.let(::applyReference)
            }
        }
    }

    private fun applyReference(reference: YarnReference) {
        _state.update {
            it.copy(
                brand = it.brand.fromCache(reference.brand),
                colorCode = it.colorCode.fromCache(reference.colorCode),
                colorName = it.colorName.fromCache(reference.colorName),
                knownReference = true,
            )
        }
        refreshSuggestions()
    }

    private suspend fun loadLots(ean: String) {
        // Au comptage, un bain épuisé existe toujours et peut être recompté.
        val lots = try {
            repository.lots(ean, includeEmpty = route.mode == ScanMode.INVENTORY)
        } catch (e: ApiException) {
            _state.update { it.copy(error = e.toErrorMessage()) }
            return
        }
        _state.update { state ->
            // Sortie sans bain lu et un seul bain en stock : c'est forcément celui-là. Pas au
            // comptage, où l'étiquette peut être celle d'un nouveau bain.
            val single = lots.singleOrNull()?.takeIf { route.mode == ScanMode.EXIT }
            val dyeLot = if (state.dyeLot.source == FieldSource.EMPTY && single != null) {
                FieldState(single.dyeLot, FieldSource.STOCK)
            } else {
                state.dyeLot
            }
            state.copy(lots = lots, dyeLot = dyeLot)
        }
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val rawEan = current.ean.value.trim()
        val ean = Gtin.normalize(rawEan)
        val eanError = when {
            rawEan.isEmpty() -> ErrorMessage.Resource(Res.string.validation_ean_required)
            ean == null -> ErrorMessage.Resource(Res.string.validation_ean_format)
            else -> null
        }
        // Un comptage peut valoir zéro : le bain est vide.
        val minimum = if (current.mode == ScanMode.INVENTORY) 0 else 1
        val quantity = current.quantity.toIntOrNull()?.takeIf { it in minimum..MAX_QUANTITY }
        val recipient = current.recipient.trim()
        val recipientError = if (current.mode == ScanMode.EXIT && recipient.isEmpty()) {
            ErrorMessage.Resource(Res.string.validation_recipient_required)
        } else {
            null
        }
        // Sortie : le stock connu du bain borne la quantité (le serveur revérifie).
        val available = if (current.mode == ScanMode.EXIT && current.lots != null) current.selectedLot?.quantity ?: 0 else null
        val quantityError = when {
            quantity == null -> ErrorMessage.Resource(
                if (current.mode == ScanMode.INVENTORY) Res.string.validation_count else Res.string.validation_quantity,
            )
            available != null && quantity > available -> ErrorMessage.Plural(Res.plurals.error_insufficient_stock, available)
            else -> null
        }
        if (ean == null || quantity == null || eanError != null || quantityError != null || recipientError != null) {
            _state.update { it.copy(eanError = eanError, quantityError = quantityError, recipientError = recipientError) }
            return
        }
        send(current, ean, quantity, recipient, CountMode.NEW)
    }

    /** Réponse à un bain déjà compté : ADD ajoute au comptage, SET le remplace. */
    fun resolveDuplicate(mode: CountMode) {
        val current = _state.value
        val ean = Gtin.normalize(current.ean.value.trim()) ?: return
        val quantity = current.duplicate?.quantity ?: return
        _state.update { it.copy(duplicate = null) }
        send(current, ean, quantity, "", mode)
    }

    fun dismissDuplicate() = _state.update { it.copy(duplicate = null) }

    private fun send(current: ConfirmUiState, ean: String, quantity: Int, recipient: String, countMode: CountMode) {
        _state.update { it.copy(saving = true, error = null) }
        val dyeLot = current.dyeLot.value.trim()
        val reference = if (current.referenceEditable) {
            ReferenceFields(
                brand = current.brand.value.trim().ifEmpty { null },
                colorCode = current.colorCode.value.trim().ifEmpty { null },
                colorName = current.colorName.value.trim().ifEmpty { null },
            )
        } else {
            null
        }
        val requestId = requestIdFor(
            listOf(current.mode.name, ean, dyeLot, quantity.toString(), recipient, countMode.name) +
                listOf(reference?.brand, reference?.colorCode, reference?.colorName),
        )
        viewModelScope.launch {
            try {
                // Ce que le serveur a enregistré, qui fait foi : la confirmation le reprend.
                val saved = when (current.mode) {
                    ScanMode.ENTRY, ScanMode.EXIT -> {
                        val kind = if (current.mode == ScanMode.ENTRY) MovementKind.IN else MovementKind.OUT
                        val exitRecipient = recipient.takeIf { kind == MovementKind.OUT }
                        val input = MovementInput(kind, ean, dyeLot, quantity, exitRecipient, reference)
                        val movement = repository.record(input, requestId).movement
                        SavedScan(current.mode, movement.quantity, movement.dyeLot, movement.recipient)
                    }
                    ScanMode.INVENTORY -> {
                        val inventoryId = checkNotNull(route.inventoryId) { "inventory scan without inventory" }
                        val response = repository.count(inventoryId, CountInput(ean, dyeLot, quantity, reference), countMode, requestId)
                        SavedScan(current.mode, response.count.quantity, response.count.dyeLot)
                    }
                }
                _saved.send(saved)
            } catch (e: ApiException) {
                val error = (e as? ApiException.Server)?.error
                if (error?.code == "error.already_counted") {
                    _state.update { it.copy(saving = false, duplicate = DuplicateCount(error.counted ?: 0, quantity)) }
                    return@launch
                }
                val message = e.toErrorMessage()
                _state.update {
                    when (error?.field) {
                        "quantity" -> it.copy(quantityError = message)
                        "recipient" -> it.copy(recipientError = message)
                        "ean" -> it.copy(eanError = message)
                        else -> it.copy(error = message)
                    }.copy(saving = false)
                }
                // Quelqu'un a sorti des pelotes entre-temps : on recharge les bains.
                if (current.mode == ScanMode.EXIT && error?.field == "quantity") loadLots(ean)
            }
        }
    }

    /** L'identifiant d'envoi de ce contenu, gardé avec lui dans l'état sauvegardé de l'écran. */
    private fun requestIdFor(content: List<String?>): String {
        val key = content.joinToString("\u0000") { it ?: "\u0001" }
        return requestIds.idFor(key).also {
            savedStateHandle[KEY_REQUEST_CONTENT] = key
            savedStateHandle[KEY_REQUEST_ID] = it
        }
    }

    private fun field(value: String?, source: FieldSource) =
        if (value.isNullOrBlank()) FieldState() else FieldState(value, source)

    private fun FieldState.fromCache(cached: String?) =
        if (source == FieldSource.MANUAL || cached.isNullOrBlank()) this else FieldState(cached, FieldSource.CACHE)

    companion object {
        private const val KEY_REQUEST_ID = "request_id"
        private const val KEY_REQUEST_CONTENT = "request_content"
        private const val MAX_QUANTITY = 10_000
    }
}
