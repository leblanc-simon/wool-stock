package eu.leblancsimon.woolstock.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.leblancsimon.woolstock.R
import eu.leblancsimon.woolstock.data.api.StockLot
import eu.leblancsimon.woolstock.ui.ErrorMessage
import eu.leblancsimon.woolstock.ui.text

/** Stock global, par référence puis par bain. Les mouvements passent par Entrée et Sortie. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockScreen(onExit: (StockLot) -> Unit, viewModel: StockViewModel = viewModel(factory = StockViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // À chaque retour sur l'onglet : d'autres téléphones ont pu modifier le stock.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // L'en-tête (logo, titre, réglages) est commun aux onglets.
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        val groups = state.groups
        val error = state.error
        when {
            groups == null && error != null -> MessageState(
                message = error.text(),
                action = stringResource(R.string.common_retry) to viewModel::refresh,
            )
            groups == null -> Unit
            groups.isEmpty() && error == null -> MessageState(stringResource(R.string.stock_empty))
            else -> StockList(groups, error, onExit)
        }
    }
}

@Composable
private fun StockList(groups: List<ReferenceGroup>, error: ErrorMessage?, onExit: (StockLot) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) {
            item(key = "error") {
                Text(error.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(groups, key = { it.ean }) { group -> ReferenceCard(group, onExit) }
    }
}

@Composable
private fun ReferenceCard(group: ReferenceGroup, onExit: (StockLot) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.brand ?: stringResource(R.string.stock_unknown_brand),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = listOfNotNull(group.colorCode, group.colorName).joinToString(" ")
                        .ifEmpty { stringResource(R.string.stock_unknown_color) },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(group.ean, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(
                text = pluralStringResource(R.plurals.stock_balls, group.total, group.total),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        group.lots.forEachIndexed { index, lot ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            LotRow(lot, onExit = { onExit(lot) })
        }
    }
}

@Composable
private fun LotRow(lot: StockLot, onExit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (lot.dyeLot.isEmpty()) stringResource(R.string.stock_no_dye_lot) else stringResource(R.string.stock_dye_lot, lot.dyeLot),
            modifier = Modifier.weight(1f),
        )
        Text(pluralStringResource(R.plurals.stock_balls, lot.quantity, lot.quantity), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onExit) { Text(stringResource(R.string.stock_exit)) }
    }
}

/** Message plein écran, défilable pour que le geste de rafraîchissement reste possible. */
@Composable
private fun MessageState(
    message: String,
    action: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(message, textAlign = TextAlign.Center)
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick) { Text(label) }
        }
        secondary?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
    }
}
