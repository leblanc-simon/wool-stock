package io.leblanc.woolstock.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.leblanc.woolstock.data.api.User
import io.leblanc.woolstock.data.api.UserRole
import io.leblanc.woolstock.resources.Res
import io.leblanc.woolstock.resources.common_back
import io.leblanc.woolstock.resources.role_admin
import io.leblanc.woolstock.resources.role_manager
import io.leblanc.woolstock.resources.users_add
import io.leblanc.woolstock.resources.users_created
import io.leblanc.woolstock.resources.users_email
import io.leblanc.woolstock.resources.users_list
import io.leblanc.woolstock.resources.users_name
import io.leblanc.woolstock.resources.users_name_help
import io.leblanc.woolstock.resources.users_role_admin_help
import io.leblanc.woolstock.resources.users_role_manager_help
import io.leblanc.woolstock.resources.users_submit
import io.leblanc.woolstock.resources.users_title
import io.leblanc.woolstock.ui.appViewModel
import io.leblanc.woolstock.ui.text
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Comptes autorisés, et ajout d'une adresse : réservé aux admins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(onBack: () -> Unit, viewModel: UsersViewModel = appViewModel { UsersViewModel(it.stockRepository) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.users_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "form") { NewUserForm(state, viewModel) }
            item(key = "list-title") {
                Text(
                    text = stringResource(Res.string.users_list),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            state.loadError?.let { error ->
                item(key = "error") { Text(error.text(), color = MaterialTheme.colorScheme.error) }
            }
            items(state.users.orEmpty(), key = { it.id }) { user -> UserRow(user) }
        }
    }
}

@Composable
private fun NewUserForm(state: UsersUiState, viewModel: UsersViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.users_add), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text(stringResource(Res.string.users_email)) },
            isError = state.emailError != null,
            supportingText = state.emailError?.let { error -> { Text(error.text()) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            label = { Text(stringResource(Res.string.users_name)) },
            isError = state.nameError != null,
            supportingText = { Text(state.nameError?.text() ?: stringResource(Res.string.users_name_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        Column(Modifier.selectableGroup()) {
            RoleOption(UserRole.MANAGER, Res.string.role_manager, Res.string.users_role_manager_help, state.role, viewModel::onRoleChange)
            RoleOption(UserRole.ADMIN, Res.string.role_admin, Res.string.users_role_admin_help, state.role, viewModel::onRoleChange)
        }
        state.formError?.let { error -> Text(error.text(), color = MaterialTheme.colorScheme.error) }
        state.created?.let { user ->
            Text(stringResource(Res.string.users_created, user.name), color = MaterialTheme.colorScheme.primary)
        }
        Button(onClick = viewModel::create, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.users_submit))
        }
    }
}

@Composable
private fun RoleOption(
    role: UserRole,
    title: StringResource,
    help: StringResource,
    selected: UserRole,
    onSelect: (UserRole) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = role == selected, onClick = { onSelect(role) }, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = role == selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UserRow(user: User) {
    Column {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(user.name, style = MaterialTheme.typography.bodyLarge)
                Text(user.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = stringResource(if (user.isAdmin) Res.string.role_admin else Res.string.role_manager),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
