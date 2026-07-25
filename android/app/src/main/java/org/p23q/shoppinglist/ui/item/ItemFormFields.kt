package org.p23q.shoppinglist.ui.item

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Category/stores/quantity/price/note fields shared by [AddItemDialog] and [EditItemDialog]. */
@Composable
internal fun ItemFormFields(state: ItemFormUiState, viewModel: ItemFormViewModel) {
    OutlinedTextField(
        value = state.category,
        onValueChange = viewModel::onCategoryChange,
        label = { Text("Category") },
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
    Text("Stores")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.storeInput,
            onValueChange = viewModel::onStoreInputChange,
            label = { Text("Add store") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = viewModel::addStore) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add store")
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
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Remove $store")
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
        label = { Text("Quantity") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.priceAmount,
            onValueChange = viewModel::onPriceAmountChange,
            label = { Text("Price") },
            singleLine = true,
            isError = state.priceError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = state.priceCurrency,
            onValueChange = viewModel::onPriceCurrencyChange,
            label = { Text("Currency") },
            singleLine = true,
            isError = state.currencyError != null,
            modifier = Modifier.widthIn(min = 88.dp),
        )
    }
    (state.priceError ?: state.currencyError)?.let { error ->
        Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))
    }

    OutlinedTextField(
        value = state.note,
        onValueChange = viewModel::onNoteChange,
        label = { Text("Note") },
        modifier = Modifier.fillMaxWidth(),
    )
}
