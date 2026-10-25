package eu.leblancsimon.woolstock.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.leblancsimon.woolstock.R
import eu.leblancsimon.woolstock.data.api.Session
import eu.leblancsimon.woolstock.ui.ErrorMessage
import eu.leblancsimon.woolstock.ui.text

/** Réglages : le compte connecté (avec la gestion des comptes pour un admin), puis le serveur. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenUsers: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel) { viewModel.saved.collect { currentOnBack() } }

    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteAccount,
            title = { Text(stringResource(R.string.settings_delete_account_title)) },
            text = { Text(stringResource(R.string.settings_delete_account_text)) },
            confirmButton = {
                TextButton(
                    onClick = viewModel::deleteAccount,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.settings_delete_account_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteAccount) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            session?.let { current ->
                Account(current, onOpenUsers = onOpenUsers, onLogout = viewModel::logout)
                DeleteAccount(
                    deleting = state.deleting,
                    error = state.deleteError,
                    onDelete = viewModel::askDeleteAccount,
                )
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
            }
            Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                label = { Text(stringResource(R.string.settings_server_url)) },
                isError = state.urlError != null,
                supportingText = { Text(state.urlError?.text() ?: stringResource(R.string.settings_server_url_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            when (val test = state.test) {
                is ConnectionTest.Ok -> Text(
                    text = stringResource(R.string.settings_test_ok, test.version),
                    color = MaterialTheme.colorScheme.primary,
                )
                is ConnectionTest.Failed -> Text(test.message.text(), color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = viewModel::test, enabled = !state.testing, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_test))
                }
                Button(onClick = viewModel::save, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
    }
}

/** Le compte connecté : son nom signe chaque entrée, sortie et comptage saisi ici. */
@Composable
private fun Account(session: Session, onOpenUsers: () -> Unit, onLogout: () -> Unit) {
    val user = session.user
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium)
        Text(user.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(
                R.string.settings_account_detail,
                user.email,
                stringResource(if (user.isAdmin) R.string.role_admin else R.string.role_manager),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_account_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (user.isAdmin) {
            Button(onClick = onOpenUsers, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.settings_users))
            }
        }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(stringResource(R.string.settings_logout))
        }
    }
}

/** Suppression du compte par son titulaire, exigée par les stores ; confirmée par un dialogue. */
@Composable
private fun DeleteAccount(deleting: Boolean, error: ErrorMessage?, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = onDelete,
            enabled = !deleting,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_delete_account))
        }
        error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}
