package eu.leblancsimon.woolstock.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.leblancsimon.woolstock.data.AppSettings
import eu.leblancsimon.woolstock.data.StockRepository
import eu.leblancsimon.woolstock.data.api.ApiException
import eu.leblancsimon.woolstock.resources.Res
import eu.leblancsimon.woolstock.resources.validation_email
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

data class LoginUiState(
    val email: String = "",
    val error: ErrorMessage? = null,
    val loading: Boolean = false,
)

class LoginViewModel(private val repository: StockRepository, settings: AppSettings) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** Adresse du serveur visé, rappelée sous le formulaire. */
    val serverUrl: StateFlow<String> = settings.serverUrl

    private val _loggedIn = Channel<Unit>(Channel.CONFLATED)
    val loggedIn: Flow<Unit> = _loggedIn.receiveAsFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    /** Une adresse autorisée ouvre une session, gardée sur le téléphone sans limite de durée. */
    fun login() {
        val email = _state.value.email.trim()
        if (_state.value.loading) return
        if (!email.contains('@')) {
            _state.update { it.copy(error = ErrorMessage.Resource(Res.string.validation_email)) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                repository.login(email)
                _loggedIn.send(Unit)
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.toErrorMessage()) }
            }
        }
    }
}
