package eu.leblancsimon.woolstock.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.leblancsimon.woolstock.WoolStockApp
import eu.leblancsimon.woolstock.data.StockRepository
import eu.leblancsimon.woolstock.data.api.ApiException
import eu.leblancsimon.woolstock.data.api.InventoryScope
import eu.leblancsimon.woolstock.data.api.InventoryState
import eu.leblancsimon.woolstock.ui.ErrorMessage
import eu.leblancsimon.woolstock.ui.toErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InventoryUiState(
    /** false tant que le serveur n'a pas répondu une première fois. */
    val loaded: Boolean = false,
    /** Inventaire en cours, partagé par tous les téléphones ; null s'il n'y en a pas. */
    val current: InventoryState? = null,
    val refreshing: Boolean = false,
    val busy: Boolean = false,
    /** Type choisi pour le prochain inventaire. */
    val scope: InventoryScope = InventoryScope.PARTIAL,
    val confirmClose: Boolean = false,
    /** Bilan de l'inventaire qui vient d'être terminé ici. */
    val justClosed: ClosedSummary? = null,
    val error: ErrorMessage? = null,
)

/** Bilan d'un inventaire terminé : bains comptés, et bains non comptés remis à 0. */
data class ClosedSummary(val counted: Int, val zeroed: Int)

class InventoryViewModel(private val repository: StockRepository) : ViewModel() {
    private val _state = MutableStateFlow(InventoryUiState())
    val state: StateFlow<InventoryUiState> = _state.asStateFlow()

    /** Relu à chaque retour sur l'onglet : d'autres téléphones comptent en même temps. */
    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(refreshing = true) }
        try {
            val current = repository.currentInventory()
            _state.update { it.copy(loaded = true, current = current, refreshing = false, error = null) }
        } catch (e: ApiException) {
            _state.update { it.copy(refreshing = false, error = e.toErrorMessage()) }
        }
    }

    fun onScopeChange(scope: InventoryScope) = _state.update { it.copy(scope = scope) }

    fun start() = action {
        val started = try {
            repository.startInventory(_state.value.scope)
        } catch (e: ApiException.Server) {
            // Lancé entre-temps depuis un autre téléphone : on le rejoint.
            if (e.error?.code != "error.inventory_open") throw e
            repository.currentInventory()
        }
        // Le choix revient sur partiel : un inventaire complet remet des bains à 0, il se choisit à chaque fois.
        _state.update { it.copy(current = started, justClosed = null, scope = InventoryScope.PARTIAL) }
    }

    fun askClose() = _state.update { it.copy(confirmClose = true) }

    fun dismissClose() = _state.update { it.copy(confirmClose = false) }

    fun close() {
        val id = _state.value.current?.inventory?.id ?: return
        _state.update { it.copy(confirmClose = false) }
        action {
            val closed = repository.closeInventory(id)
            val zeroed = closed.counts.count { it.zeroed }
            _state.update { it.copy(current = null, justClosed = ClosedSummary(closed.counts.size - zeroed, zeroed)) }
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.toErrorMessage()) }
                // Terminé depuis un autre téléphone : on affiche l'état réel.
                if ((e as? ApiException.Server)?.error?.code == "error.inventory_closed") refresh()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { InventoryViewModel((this[APPLICATION_KEY] as WoolStockApp).container.stockRepository) }
        }
    }
}
