package eu.leblancsimon.woolstock.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.leblancsimon.woolstock.R
import eu.leblancsimon.woolstock.WoolStockApp
import eu.leblancsimon.woolstock.data.AppSettings
import eu.leblancsimon.woolstock.data.StockRepository
import eu.leblancsimon.woolstock.data.api.ApiException
import eu.leblancsimon.woolstock.data.api.Session
import eu.leblancsimon.woolstock.ui.ErrorMessage
import eu.leblancsimon.woolstock.ui.toErrorMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URISyntaxException

sealed interface ConnectionTest {
    data class Ok(val version: String) : ConnectionTest

    data class Failed(val message: ErrorMessage) : ConnectionTest
}

data class SettingsUiState(
    val serverUrl: String,
    val urlError: ErrorMessage? = null,
    val testing: Boolean = false,
    val test: ConnectionTest? = null,
    /** Le dialogue de confirmation de « Supprimer mon compte » est ouvert. */
    val confirmingDelete: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: ErrorMessage? = null,
)

class SettingsViewModel(private val settings: AppSettings, private val repository: StockRepository) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(settings.serverUrl.value))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Compte connecté sur ce téléphone, ou null. */
    val session: StateFlow<Session?> = settings.session

    private val _saved = Channel<Unit>(Channel.CONFLATED)
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    fun onServerUrlChange(value: String) = _state.update { it.copy(serverUrl = value, urlError = null, test = null) }

    /** Interroge /api/health à l'adresse saisie, sans l'enregistrer. */
    fun test() {
        val url = validUrl() ?: return
        _state.update { it.copy(testing = true, test = null) }
        viewModelScope.launch {
            val result = try {
                ConnectionTest.Ok(repository.checkServer(url).version)
            } catch (e: ApiException) {
                ConnectionTest.Failed(e.toErrorMessage())
            }
            _state.update { it.copy(testing = false, test = result) }
        }
    }

    fun save() {
        val url = validUrl() ?: return
        settings.saveServerUrl(url)
        _saved.trySend(Unit)
    }

    /** La navigation revient d'elle-même à l'écran de connexion quand la session disparaît. */
    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    fun askDeleteAccount() = _state.update { it.copy(confirmingDelete = true, deleteError = null) }

    fun dismissDeleteAccount() = _state.update { it.copy(confirmingDelete = false) }

    /** Comme pour la déconnexion, la navigation suit la disparition de la session. */
    fun deleteAccount() {
        _state.update { it.copy(confirmingDelete = false, deleting = true, deleteError = null) }
        viewModelScope.launch {
            val error = try {
                repository.deleteAccount()
                null
            } catch (e: ApiException) {
                e.toErrorMessage()
            }
            _state.update { it.copy(deleting = false, deleteError = error) }
        }
    }

    /** Adresse http(s) avec un hôte, sans barre finale ; sinon signale l'erreur. */
    private fun validUrl(): String? {
        val url = _state.value.serverUrl.trim().trimEnd('/')
        val valid = try {
            val uri = URI(url)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()
        } catch (_: URISyntaxException) {
            false
        }
        if (!valid) {
            _state.update { it.copy(urlError = ErrorMessage.Resource(R.string.validation_url)) }
            return null
        }
        return url
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as WoolStockApp).container
                SettingsViewModel(container.settings, container.stockRepository)
            }
        }
    }
}
