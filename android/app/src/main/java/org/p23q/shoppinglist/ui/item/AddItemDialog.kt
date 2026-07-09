package org.p23q.shoppinglist.ui.item

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Notes (Add-item dialog): name suggestions from the registry; picking one reuses it, else creates new. */
@Composable
fun AddItemDialog(
    listId: String,
    onDismiss: () -> Unit,
    viewModel: ItemFormViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(listId) { viewModel.startAdd(listId) }
    LaunchedEffect(state.isSaved) { if (state.isSaved) onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add item") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    isError = state.nameError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.nameError?.let { error ->
                    Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.itemId == null && state.suggestions.isNotEmpty()) {
                    Column {
                        state.suggestions.forEach { suggestion ->
                            Text(
                                text = suggestion.name.value,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.pickSuggestion(suggestion) }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ItemFormFields(state = state, viewModel = viewModel)
            }
        },
        confirmButton = { TextButton(onClick = viewModel::save) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
