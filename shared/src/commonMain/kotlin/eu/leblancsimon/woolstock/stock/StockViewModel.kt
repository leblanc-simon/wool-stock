package eu.leblancsimon.woolstock.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.leblancsimon.woolstock.data.StockRepository
import eu.leblancsimon.woolstock.data.api.ApiException
import eu.leblancsimon.woolstock.data.api.StockLot
import eu.leblancsimon.woolstock.ui.ErrorMessage
import eu.leblancsimon.woolstock.ui.toErrorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Une référence (EAN) et ses bains en stock : deux qualités d'une marque peuvent partager un n° de coloris. */
data class ReferenceGroup(
    val ean: String,
    val brand: String?,
    val colorCode: String?,
    val colorName: String?,
    val lots: List<StockLot>,
) {
    val total: Int get() = lots.sumOf { it.quantity }
}

/** Regroupe les bains par référence, dans l'ordre du serveur (marque, coloris, bain). */
fun groupStock(lots: List<StockLot>): List<ReferenceGroup> = lots
    .groupBy { it.ean }
    .map { (ean, referenceLots) ->
        val first = referenceLots.first()
        ReferenceGroup(ean, first.brand?.trim()?.ifEmpty { null }, first.colorCode, first.colorName, referenceLots)
    }

data class StockUiState(
    /** null tant que le stock n'a jamais été chargé. */
    val groups: List<ReferenceGroup>? = null,
    val refreshing: Boolean = false,
    val error: ErrorMessage? = null,
)

class StockViewModel(private val repository: StockRepository) : ViewModel() {
    private val _state = MutableStateFlow(StockUiState())
    val state: StateFlow<StockUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    /** Recharge le stock global, et au passage la copie locale du cache des références. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                val lots = repository.stock()
                _state.update { StockUiState(groups = groupStock(lots)) }
            } catch (e: ApiException) {
                _state.update { it.copy(refreshing = false, error = e.toErrorMessage()) }
                return@launch
            }
            try {
                repository.syncReferences()
            } catch (_: ApiException) {
                // Le stock est à jour ; le cache sera recopié au prochain rafraîchissement.
            }
        }
    }
}
