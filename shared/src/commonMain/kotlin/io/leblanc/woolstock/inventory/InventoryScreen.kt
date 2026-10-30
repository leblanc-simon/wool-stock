package io.leblanc.woolstock.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.leblanc.woolstock.data.api.Inventory
import io.leblanc.woolstock.data.api.InventoryCount
import io.leblanc.woolstock.data.api.InventoryScope
import io.leblanc.woolstock.data.api.InventoryState
import io.leblanc.woolstock.platform.formatDateTime
import io.leblanc.woolstock.resources.Res
import io.leblanc.woolstock.resources.common_cancel
import io.leblanc.woolstock.resources.inventory_close
import io.leblanc.woolstock.resources.inventory_close_confirm
import io.leblanc.woolstock.resources.inventory_close_text
import io.leblanc.woolstock.resources.inventory_close_text_full
import io.leblanc.woolstock.resources.inventory_close_text_full_none
import io.leblanc.woolstock.resources.inventory_close_title
import io.leblanc.woolstock.resources.inventory_closed
import io.leblanc.woolstock.resources.inventory_closed_zeroed
import io.leblanc.woolstock.resources.inventory_counted
import io.leblanc.woolstock.resources.inventory_in_progress_full
import io.leblanc.woolstock.resources.inventory_in_progress_partial
import io.leblanc.woolstock.resources.inventory_intro
import io.leblanc.woolstock.resources.inventory_no_counts
import io.leblanc.woolstock.resources.inventory_none
import io.leblanc.woolstock.resources.inventory_previous
import io.leblanc.woolstock.resources.inventory_scan
import io.leblanc.woolstock.resources.inventory_scope_full
import io.leblanc.woolstock.resources.inventory_scope_full_help
import io.leblanc.woolstock.resources.inventory_scope_partial
import io.leblanc.woolstock.resources.inventory_scope_partial_help
import io.leblanc.woolstock.resources.inventory_start
import io.leblanc.woolstock.resources.inventory_started_at
import io.leblanc.woolstock.resources.inventory_started_by
import io.leblanc.woolstock.resources.inventory_uncounted
import io.leblanc.woolstock.resources.stock_balls
import io.leblanc.woolstock.resources.stock_dye_lot
import io.leblanc.woolstock.resources.stock_no_dye_lot
import io.leblanc.woolstock.resources.stock_unknown_brand
import io.leblanc.woolstock.resources.stock_unknown_color
import io.leblanc.woolstock.ui.appViewModel
import io.leblanc.woolstock.ui.text
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * Onglet Inventaire : on lance un inventaire, on scanne chaque bain et on saisit la
 * quantité comptée ; le stock la prend aussitôt. Plusieurs téléphones peuvent compter
 * le même inventaire.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(onScan: (Long) -> Unit, viewModel: InventoryViewModel = appViewModel { InventoryViewModel(it.stockRepository) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val closing = state.current
    if (state.confirmClose && closing != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClose,
            title = { Text(stringResource(Res.string.inventory_close_title)) },
            text = { Text(closeWarning(closing)) },
            confirmButton = { TextButton(onClick = viewModel::close) { Text(stringResource(Res.string.inventory_close_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissClose) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }

    // L'en-tête (logo, titre, réglages) est commun aux onglets.
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        val current = state.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                item(key = "error") {
                    Text(error.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            when {
                current != null -> {
                    item(key = "session") {
                        Session(current, state.busy, onScan = { onScan(current.inventory.id) }, onClose = viewModel::askClose)
                    }
                    item(key = "counted") {
                        Text(
                            text = if (current.counts.isEmpty()) {
                                stringResource(Res.string.inventory_no_counts)
                            } else {
                                pluralStringResource(Res.plurals.inventory_counted, current.counts.size, current.counts.size)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    items(current.counts, key = { it.lotId }) { count -> CountRow(count) }
                }
                state.loaded -> item(key = "start") {
                    NoInventory(state, onScopeChange = viewModel::onScopeChange, onStart = viewModel::start)
                }
            }
        }
    }
}

/** À la clôture : un inventaire complet remet à 0 ce qui n'a pas été compté, on le dit. */
@Composable
private fun closeWarning(current: InventoryState): String = when {
    !current.inventory.isFull -> stringResource(Res.string.inventory_close_text)
    current.uncounted == 0 -> stringResource(Res.string.inventory_close_text_full_none)
    else -> pluralStringResource(Res.plurals.inventory_close_text_full, current.uncounted, current.uncounted)
}

@Composable
private fun Session(current: InventoryState, busy: Boolean, onScan: () -> Unit, onClose: () -> Unit) {
    val inventory: Inventory = current.inventory
    val startedAt = rememberDateTime(inventory.startedAt)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(if (inventory.isFull) Res.string.inventory_in_progress_full else Res.string.inventory_in_progress_partial),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = inventory.startedBy?.let { stringResource(Res.string.inventory_started_by, it, startedAt) }
                ?: stringResource(Res.string.inventory_started_at, startedAt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (inventory.isFull && current.uncounted > 0) {
            Text(
                text = pluralStringResource(Res.plurals.inventory_uncounted, current.uncounted, current.uncounted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(Res.string.inventory_scan))
        }
        OutlinedButton(onClick = onClose, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.inventory_close))
        }
    }
}

@Composable
private fun NoInventory(state: InventoryUiState, onScopeChange: (InventoryScope) -> Unit, onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.justClosed?.let { closed ->
            Column {
                Text(
                    text = pluralStringResource(Res.plurals.inventory_closed, closed.counted, closed.counted),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (closed.zeroed > 0) {
                    Text(
                        text = pluralStringResource(Res.plurals.inventory_closed_zeroed, closed.zeroed, closed.zeroed),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Text(stringResource(Res.string.inventory_none), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.inventory_intro), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.selectableGroup()) {
            ScopeOption(InventoryScope.PARTIAL, Res.string.inventory_scope_partial, Res.string.inventory_scope_partial_help, state.scope, onScopeChange)
            ScopeOption(InventoryScope.FULL, Res.string.inventory_scope_full, Res.string.inventory_scope_full_help, state.scope, onScopeChange)
        }
        Button(onClick = onStart, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.inventory_start))
        }
    }
}

@Composable
private fun ScopeOption(
    scope: InventoryScope,
    title: StringResource,
    help: StringResource,
    selected: InventoryScope,
    onSelect: (InventoryScope) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = scope == selected, onClick = { onSelect(scope) }, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = scope == selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Bain compté : la quantité comptée, et le stock d'avant pour voir l'écart. */
@Composable
private fun CountRow(count: InventoryCount) {
    Column {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = count.brand ?: stringResource(Res.string.stock_unknown_brand),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = listOfNotNull(count.colorCode, count.colorName).joinToString(" ")
                        .ifEmpty { stringResource(Res.string.stock_unknown_color) },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (count.dyeLot.isEmpty()) stringResource(Res.string.stock_no_dye_lot) else stringResource(Res.string.stock_dye_lot, count.dyeLot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = pluralStringResource(Res.plurals.stock_balls, count.quantity, count.quantity),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.inventory_previous, count.previousQuantity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Date et heure au format de la langue du téléphone, dans son fuseau. */
@Composable
private fun rememberDateTime(value: String): String = remember(value) {
    runCatching {
        formatDateTime(Instant.parse(value).toEpochMilliseconds())
    }.getOrDefault(value)
}
