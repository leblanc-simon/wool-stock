package io.leblanc.woolstock.confirm

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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import io.leblanc.woolstock.data.api.CountMode
import io.leblanc.woolstock.data.api.StockLot
import io.leblanc.woolstock.resources.Res
import io.leblanc.woolstock.resources.common_back
import io.leblanc.woolstock.resources.common_cancel
import io.leblanc.woolstock.resources.confirm_brand
import io.leblanc.woolstock.resources.confirm_color_code
import io.leblanc.woolstock.resources.confirm_color_name
import io.leblanc.woolstock.resources.confirm_counted_quantity
import io.leblanc.woolstock.resources.confirm_duplicate_add
import io.leblanc.woolstock.resources.confirm_duplicate_replace
import io.leblanc.woolstock.resources.confirm_duplicate_text
import io.leblanc.woolstock.resources.confirm_duplicate_title
import io.leblanc.woolstock.resources.confirm_dye_lot
import io.leblanc.woolstock.resources.confirm_ean
import io.leblanc.woolstock.resources.confirm_known_reference
import io.leblanc.woolstock.resources.confirm_lot_not_in_stock
import io.leblanc.woolstock.resources.confirm_lots_in_stock
import io.leblanc.woolstock.resources.confirm_no_stock
import io.leblanc.woolstock.resources.confirm_quantity
import io.leblanc.woolstock.resources.confirm_raw_value
import io.leblanc.woolstock.resources.confirm_recipient
import io.leblanc.woolstock.resources.confirm_recipient_help
import io.leblanc.woolstock.resources.confirm_save_entry
import io.leblanc.woolstock.resources.confirm_save_exit
import io.leblanc.woolstock.resources.confirm_save_inventory
import io.leblanc.woolstock.resources.confirm_source
import io.leblanc.woolstock.resources.confirm_title_entry
import io.leblanc.woolstock.resources.confirm_title_exit
import io.leblanc.woolstock.resources.confirm_title_inventory
import io.leblanc.woolstock.resources.format_lot_quantity
import io.leblanc.woolstock.resources.stock_balls
import io.leblanc.woolstock.resources.stock_no_dye_lot
import io.leblanc.woolstock.ui.ErrorMessage
import io.leblanc.woolstock.ui.SavedScan
import io.leblanc.woolstock.ui.ScanMode
import io.leblanc.woolstock.ui.appViewModel
import io.leblanc.woolstock.ui.text
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmScreen(
    onSaved: (SavedScan) -> Unit,
    onCancel: () -> Unit,
    viewModel: ConfirmViewModel = appViewModel { ConfirmViewModel(createSavedStateHandle(), it.stockRepository) },
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
                                ScanMode.ENTRY -> Res.string.confirm_title_entry
                                ScanMode.EXIT -> Res.string.confirm_title_exit
                                ScanMode.INVENTORY -> Res.string.confirm_title_inventory
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back))
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
                    text = stringResource(Res.string.confirm_known_reference),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SourcedField(
                label = Res.string.confirm_ean,
                field = state.ean,
                onValueChange = { viewModel.onChange(ConfirmField.EAN, it) },
                error = state.eanError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            // Une sortie ne modifie pas le cache : marque et coloris sont affichés pour vérifier.
            val readOnly = !state.referenceEditable
            SourcedField(
                label = Res.string.confirm_brand,
                field = state.brand,
                onValueChange = { viewModel.onChange(ConfirmField.BRAND, it) },
                readOnly = readOnly,
                suggestions = state.brandSuggestions,
                onSuggestion = { viewModel.onSuggestion(ConfirmField.BRAND, it) },
            )
            SourcedField(Res.string.confirm_color_code, state.colorCode, { viewModel.onChange(ConfirmField.COLOR_CODE, it) }, readOnly = readOnly)
            SourcedField(
                label = Res.string.confirm_color_name,
                field = state.colorName,
                onValueChange = { viewModel.onChange(ConfirmField.COLOR_NAME, it) },
                readOnly = readOnly,
                suggestions = state.colorNameSuggestions,
                onSuggestion = { viewModel.onSuggestion(ConfirmField.COLOR_NAME, it) },
            )
            SourcedField(
                label = Res.string.confirm_dye_lot,
                field = state.dyeLot,
                onValueChange = { viewModel.onChange(ConfirmField.DYE_LOT, it) },
                warning = if (exit && state.lots?.isNotEmpty() == true && state.selectedLot == null) {
                    Res.string.confirm_lot_not_in_stock
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
                label = { Text(stringResource(if (inventory) Res.string.confirm_counted_quantity else Res.string.confirm_quantity)) },
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
                    label = { Text(stringResource(Res.string.confirm_recipient)) },
                    isError = state.recipientError != null,
                    supportingText = { Text(state.recipientError?.text() ?: stringResource(Res.string.confirm_recipient_help)) },
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
                    Text(stringResource(Res.string.common_cancel))
                }
                Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when (state.mode) {
                                ScanMode.ENTRY -> Res.string.confirm_save_entry
                                ScanMode.EXIT -> Res.string.confirm_save_exit
                                ScanMode.INVENTORY -> Res.string.confirm_save_inventory
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
        title = { Text(stringResource(Res.string.confirm_duplicate_title)) },
        text = { Text(pluralStringResource(Res.plurals.confirm_duplicate_text, duplicate.counted, duplicate.counted)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onResolve(CountMode.ADD) }) {
                    Text(stringResource(Res.string.confirm_duplicate_add, duplicate.counted + duplicate.quantity))
                }
                TextButton(onClick = { onResolve(CountMode.SET) }) {
                    Text(stringResource(Res.string.confirm_duplicate_replace, duplicate.quantity))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
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
            text = stringResource(Res.string.confirm_no_stock),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Text(stringResource(Res.string.confirm_lots_in_stock), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lots.forEach { lot ->
            FilterChip(
                selected = lot.lotId == selected?.lotId,
                onClick = { onSelect(lot) },
                label = {
                    Text(
                        stringResource(
                            Res.string.format_lot_quantity,
                            lot.dyeLot.ifEmpty { stringResource(Res.string.stock_no_dye_lot) },
                            pluralStringResource(Res.plurals.stock_balls, lot.quantity, lot.quantity),
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
    label: StringResource,
    field: FieldState,
    onValueChange: (String) -> Unit,
    error: ErrorMessage? = null,
    warning: StringResource? = null,
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
    label: StringResource,
    field: FieldState,
    onValueChange: (String) -> Unit,
    error: ErrorMessage?,
    warning: StringResource?,
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
                    raw != null -> stringResource(Res.string.confirm_raw_value, source, raw)
                    else -> stringResource(Res.string.confirm_source, source)
                },
                color = if (warning != null && error == null) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
            )
        },
                singleLine = true,
        keyboardOptions = keyboardOptions,
        modifier = modifier,
    )
}
