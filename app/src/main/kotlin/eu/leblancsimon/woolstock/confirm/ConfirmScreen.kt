package eu.leblancsimon.woolstock.confirm

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.leblancsimon.woolstock.R
import eu.leblancsimon.woolstock.data.api.CountMode
import eu.leblancsimon.woolstock.data.api.StockLot
import eu.leblancsimon.woolstock.ui.ErrorMessage
import eu.leblancsimon.woolstock.ui.SavedScan
import eu.leblancsimon.woolstock.ui.ScanMode
import eu.leblancsimon.woolstock.ui.text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmScreen(
    onSaved: (SavedScan) -> Unit,
    onCancel: () -> Unit,
    viewModel: ConfirmViewModel = viewModel(factory = ConfirmViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(viewModel) { viewModel.saved.collect { currentOnSaved(it) } }
    val exit = state.mode == ScanMode.EXIT
    val inventory = state.mode == ScanMode.INVENTORY
    state.duplicate?.let { duplicate ->
        DuplicateDialog(duplicate, onResolve = viewModel::resolveDuplicate, onDismiss = viewModel::dismissDuplicate)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (state.mode) {
                                ScanMode.ENTRY -> R.string.confirm_title_entry
                                ScanMode.EXIT -> R.string.confirm_title_exit
                                ScanMode.INVENTORY -> R.string.confirm_title_inventory
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
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
            if (state.knownReference) {
                Text(
                    text = stringResource(R.string.confirm_known_reference),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SourcedField(
                label = R.string.confirm_ean,
                field = state.ean,
                onValueChange = { viewModel.onChange(ConfirmField.EAN, it) },
                error = state.eanError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            // Une sortie ne modifie pas le cache : marque et coloris sont affichés pour vérifier.
            val readOnly = !state.referenceEditable
            SourcedField(
                label = R.string.confirm_brand,
                field = state.brand,
                onValueChange = { viewModel.onChange(ConfirmField.BRAND, it) },
                readOnly = readOnly,
                suggestions = state.brandSuggestions,
                onSuggestion = { viewModel.onSuggestion(ConfirmField.BRAND, it) },
            )
            SourcedField(R.string.confirm_color_code, state.colorCode, { viewModel.onChange(ConfirmField.COLOR_CODE, it) }, readOnly = readOnly)
            SourcedField(
                label = R.string.confirm_color_name,
                field = state.colorName,
                onValueChange = { viewModel.onChange(ConfirmField.COLOR_NAME, it) },
                readOnly = readOnly,
                suggestions = state.colorNameSuggestions,
                onSuggestion = { viewModel.onSuggestion(ConfirmField.COLOR_NAME, it) },
            )
            SourcedField(
                label = R.string.confirm_dye_lot,
                field = state.dyeLot,
                onValueChange = { viewModel.onChange(ConfirmField.DYE_LOT, it) },
                warning = if (exit && state.lots?.isNotEmpty() == true && state.selectedLot == null) {
                    R.string.confirm_lot_not_in_stock
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            )
            if (exit || inventory) {
                state.lots?.let { lots -> LotChoice(lots, state.selectedLot, showEmpty = exit, onSelect = viewModel::onLotSelected) }
            }
            OutlinedTextField(
                value = state.quantity,
                onValueChange = viewModel::onQuantityChange,
                label = { Text(stringResource(if (inventory) R.string.confirm_counted_quantity else R.string.confirm_quantity)) },
                isError = state.quantityError != null,
                supportingText = state.quantityError?.let { error -> { Text(error.text()) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (exit) {
                OutlinedTextField(
                    value = state.recipient,
                    onValueChange = viewModel::onRecipientChange,
                    label = { Text(stringResource(R.string.confirm_recipient)) },
                    isError = state.recipientError != null,
                    supportingText = { Text(state.recipientError?.text() ?: stringResource(R.string.confirm_recipient_help)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { error ->
                Text(error.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when (state.mode) {
                                ScanMode.ENTRY -> R.string.confirm_save_entry
                                ScanMode.EXIT -> R.string.confirm_save_exit
                                ScanMode.INVENTORY -> R.string.confirm_save_inventory
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Bain déjà compté dans l'inventaire (par ce téléphone ou un autre) : on ajoute la
 * nouvelle quantité au comptage, ou on repart de la nouvelle quantité.
 */
@Composable
private fun DuplicateDialog(duplicate: DuplicateCount, onResolve: (CountMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_duplicate_title)) },
        text = { Text(pluralStringResource(R.plurals.confirm_duplicate_text, duplicate.counted, duplicate.counted)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onResolve(CountMode.ADD) }) {
                    Text(stringResource(R.string.confirm_duplicate_add, duplicate.counted + duplicate.quantity))
                }
                TextButton(onClick = { onResolve(CountMode.SET) }) {
                    Text(stringResource(R.string.confirm_duplicate_replace, duplicate.quantity))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

/** Bains de la référence en stock : un appui choisit celui de la saisie. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LotChoice(lots: List<StockLot>, selected: StockLot?, showEmpty: Boolean, onSelect: (StockLot) -> Unit) {
    if (lots.isEmpty()) {
        // Au comptage, une référence absente du stock n'a rien d'anormal.
        if (!showEmpty) return
        Text(
            text = stringResource(R.string.confirm_no_stock),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Text(stringResource(R.string.confirm_lots_in_stock), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lots.forEach { lot ->
            FilterChip(
                selected = lot.lotId == selected?.lotId,
                onClick = { onSelect(lot) },
                label = {
                    Text(
                        stringResource(
                            R.string.format_lot_quantity,
                            lot.dyeLot.ifEmpty { stringResource(R.string.stock_no_dye_lot) },
                            pluralStringResource(R.plurals.stock_balls, lot.quantity, lot.quantity),
                        ),
                    )
                },
            )
        }
    }
}

/**
 * Champ qui indique d'où vient sa valeur (code-barre, cache, OCR, stock, saisie, vide). Avec
 * des [suggestions], une liste propose les orthographes déjà connues pendant la saisie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcedField(
    @StringRes label: Int,
    field: FieldState,
    onValueChange: (String) -> Unit,
    error: ErrorMessage? = null,
    @StringRes warning: Int? = null,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    suggestions: List<String> = emptyList(),
    onSuggestion: ((String) -> Unit)? = null,
) {
    if (onSuggestion == null || readOnly) {
        SourcedTextField(label, field, onValueChange, error, warning, readOnly, keyboardOptions, Modifier.fillMaxWidth())
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val showMenu = expanded && suggestions.isNotEmpty()
    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }) {
        SourcedTextField(
            label = label,
            field = field,
            onValueChange = {
                expanded = true
                onValueChange(it)
            },
            error = error,
            warning = warning,
            readOnly = false,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onSuggestion(suggestion)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SourcedTextField(
    @StringRes label: Int,
    field: FieldState,
    onValueChange: (String) -> Unit,
    error: ErrorMessage?,
    @StringRes warning: Int?,
    readOnly: Boolean,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier,
) {
    val source = stringResource(field.source.label)
    val raw = field.raw
    OutlinedTextField(
        value = field.value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        isError = error != null,
        readOnly = readOnly,
        supportingText = {
            Text(
                when {
                    error != null -> error.text()
                    warning != null -> stringResource(warning)
                    raw != null -> stringResource(R.string.confirm_raw_value, source, raw)
                    else -> stringResource(R.string.confirm_source, source)
                },
                color = if (warning != null && error == null) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
            )
        },
                singleLine = true,
        keyboardOptions = keyboardOptions,
        modifier = modifier,
    )
}
