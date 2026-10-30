package io.leblanc.woolstock.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.leblanc.woolstock.data.StockRepository
import io.leblanc.woolstock.data.api.ApiException
import io.leblanc.woolstock.data.api.User
import io.leblanc.woolstock.data.api.UserRole
import io.leblanc.woolstock.parser.EmailNames
import io.leblanc.woolstock.resources.Res
import io.leblanc.woolstock.resources.validation_email
import io.leblanc.woolstock.resources.validation_name_required
import io.leblanc.woolstock.ui.ErrorMessage
import io.leblanc.woolstock.ui.toErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsersUiState(
    /** null tant que la liste n'est pas chargée. */
    val users: List<User>? = null,
    val loadError: ErrorMessage? = null,
    val email: String = "",
    val name: String = "",
    /** Le nom suit l'adresse tant qu'il n'a pas été retouché. */
    val nameEdited: Boolean = false,
    val role: UserRole = UserRole.MANAGER,
    val emailError: ErrorMessage? = null,
    val nameError: ErrorMessage? = null,
    val formError: ErrorMessage? = null,
    val saving: Boolean = false,
    /** Dernier compte créé, annoncé sous le formulaire. */
    val created: User? = null,
)

class UsersViewModel(private val repository: StockRepository) : ViewModel() {
    private val _state = MutableStateFlow(UsersUiState())
    val state: StateFlow<UsersUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        try {
            val users = repository.users()
            _state.update { it.copy(users = users, loadError = null) }
        } catch (e: ApiException) {
            _state.update { it.copy(loadError = e.toErrorMessage()) }
        }
    }

    /** Le nom se préremplit d'après l'adresse : « marie.dupont@… » donne « Marie Dupont ». */
    fun onEmailChange(value: String) = _state.update {
        it.copy(
            email = value,
            name = if (it.nameEdited) it.name else EmailNames.guess(value),
            emailError = null,
            formError = null,
            created = null,
        )
    }

    fun onNameChange(value: String) = _state.update {
        it.copy(name = value, nameEdited = value.isNotBlank(), nameError = null, formError = null)
    }

    fun onRoleChange(role: UserRole) = _state.update { it.copy(role = role) }

    fun create() {
        val current = _state.value
        if (current.saving) return
        val email = current.email.trim()
        val name = current.name.trim()
        val emailError = if (!email.contains('@')) ErrorMessage.Resource(Res.string.validation_email) else null
        val nameError = if (name.isEmpty()) ErrorMessage.Resource(Res.string.validation_name_required) else null
        if (emailError != null || nameError != null) {
            _state.update { it.copy(emailError = emailError, nameError = nameError) }
            return
        }
        _state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            try {
                val user = repository.createUser(email, name, current.role)
                _state.update {
                    UsersUiState(
                        users = (it.users.orEmpty() + user).sortedBy { u -> u.name.lowercase() },
                        created = user,
                    )
                }
            } catch (e: ApiException) {
                val message = e.toErrorMessage()
                _state.update {
                    when ((e as? ApiException.Server)?.error?.field) {
                        "email" -> it.copy(emailError = message)
                        "name" -> it.copy(nameError = message)
                        else -> it.copy(formError = message)
                    }.copy(saving = false)
                }
            }
        }
    }
}
