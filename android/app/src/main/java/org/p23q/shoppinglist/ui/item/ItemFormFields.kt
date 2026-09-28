package org.p23q.shoppinglist.ui.item

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.AppFormat
import org.p23q.shoppinglist.ui.LocalizedOverlay
import org.p23q.shoppinglist.ui.appLocale
import org.p23q.shoppinglist.ui.asString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Category/stores/quantity/price/note/due fields shared by [AddItemDialog] and [EditItemDialog]. */
@Composable
internal fun ItemFormFields(state: ItemFormUiState, viewModel: ItemFormViewModel) {
    OutlinedTextField(
        value = state.category,
        onValueChange = viewModel::onCategoryChange,
        label = { Text(stringResource(R.string.item_category)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val categorySuggestions = state.categorySuggestions.filter {
        it.contains(state.category, ignoreCase = true) && !it.equals(state.category, ignoreCase = true)
    }
    if (categorySuggestions.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            categorySuggestions.forEach { suggestion ->
                AssistChip(
                    onClick = { viewModel.onCategoryChange(suggestion) },
                    label = { Text(suggestion) },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // Shopping-only fields (T-110): a checklist shows just category / note / status. Existing
    // values are preserved, merely not rendered, so converting a list is reversible.
    if (state.showShoppingFields) {
    Text(stringResource(R.string.item_stores))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.storeInput,
            onValueChange = viewModel::onStoreInputChange,
            label = { Text(stringResource(R.string.item_add_store)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = viewModel::addStore) {
            Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.item_add_store))
        }
    }
    // Existing stores on this list, offered the same way categories are (T-138) — retyping "Aldi"
    // from memory is how a typo becomes a second store that then haunts the chip row forever.
    // Already-added stores drop out rather than being offered as a no-op.
    val storeSuggestions = state.storeSuggestions.filter { suggestion ->
        suggestion.contains(state.storeInput, ignoreCase = true) &&
            state.stores.none { it.equals(suggestion, ignoreCase = true) }
    }
    if (storeSuggestions.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            storeSuggestions.forEach { suggestion ->
                AssistChip(
                    onClick = { viewModel.pickStore(suggestion) },
                    label = { Text(suggestion) },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
    }
    if (state.stores.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            state.stores.forEach { store ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(store) },
                    trailingIcon = {
                        IconButton(onClick = { viewModel.removeStore(store) }, modifier = Modifier.width(18.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.item_remove_store, store))
                        }
                    },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = state.quantity,
        onValueChange = viewModel::onQuantityChange,
        label = { Text(stringResource(R.string.item_quantity)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.priceAmount,
            onValueChange = viewModel::onPriceAmountChange,
            label = { Text(stringResource(R.string.item_price)) },
            singleLine = true,
            isError = state.priceError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = state.priceCurrency,
            onValueChange = viewModel::onPriceCurrencyChange,
            label = { Text(stringResource(R.string.item_currency)) },
            singleLine = true,
            isError = state.currencyError != null,
            modifier = Modifier.widthIn(min = 88.dp),
        )
    }
    (state.priceError ?: state.currencyError)?.let { error ->
        Text(text = error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))
    }

    OutlinedTextField(
        value = state.note,
        onValueChange = viewModel::onNoteChange,
        label = { Text(stringResource(R.string.item_note)) },
        modifier = Modifier.fillMaxWidth(),
    )

    // The due date (T-323), on a checklist only. A date an item of another kind already has stays
    // in the state and is saved back untouched, like the shopping fields on a checklist.
    // An account whose server drops the field (T-327) is offered none; a date the item already
    // has is shown, not changed.
    if (state.showDueDate && state.dueDatesSupported) {
        Spacer(Modifier.height(4.dp))
        DueRow(due = state.due, onDueChange = viewModel::onDueChange)
    } else if (state.showDueDate && state.due != null) {
        Spacer(Modifier.height(4.dp))
        ReadOnlyDueRow(due = state.due)
    }
}

/** The item's due date on an account whose server does not keep one (T-327), with the reason. */
@Composable
private fun ReadOnlyDueRow(due: String) {
    Column(modifier = Modifier.fillMaxWidth().testTag("item-due-row")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.item_due), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(AppFormat.calendarDate(due, appLocale()), style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            stringResource(R.string.item_due_needs_server),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One compact line: the label, the date (or a calendar button while there is none) opening the
 * ledger's date picker, and a clear button once there is one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueRow(due: String?, onDueChange: (String?) -> Unit) {
    var isPicking by remember { mutableStateOf(false) }
    val label = stringResource(R.string.item_due)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag("item-due-row"),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (due != null) {
            TextButton(onClick = { isPicking = true }, modifier = Modifier.testTag("item-due-pick")) {
                Text(AppFormat.calendarDate(due, appLocale()))
            }
            IconButton(onClick = { onDueChange(null) }, modifier = Modifier.testTag("item-due-clear")) {
                Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.item_due_clear))
            }
        } else {
            IconButton(onClick = { isPicking = true }, modifier = Modifier.testTag("item-due-pick")) {
                Icon(imageVector = Icons.Default.DateRange, contentDescription = label)
            }
        }
    }

    if (isPicking) {
        // As the expense form's (T-185): calendar dates, so the picker works at UTC midnight both
        // ways and no time zone can move the chosen day.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = due?.let {
                runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
            },
        )
        DatePickerDialog(
            onDismissRequest = { isPicking = false },
            confirmButton = {
                LocalizedOverlay {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onDueChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                        }
                        isPicking = false
                    }) { Text(stringResource(R.string.action_ok)) }
                }
            },
            dismissButton = {
                LocalizedOverlay {
                    TextButton(onClick = { isPicking = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        ) {
            LocalizedOverlay { DatePicker(state = pickerState) }
        }
    }
}
