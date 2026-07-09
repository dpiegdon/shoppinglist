package org.p23q.shoppinglist.ui.item

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import org.p23q.shoppinglist.data.db.Status

/** Notes (List view): the row edit icon opens this — every field including name, plus delete. */
@Composable
fun EditItemDialog(
    itemId: String,
    onDismiss: () -> Unit,
    viewModel: ItemFormViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(itemId) { viewModel.startEdit(itemId) }
    LaunchedEffect(state.isSaved) { if (state.isSaved) onDismiss() }
    LaunchedEffect(state.isDeleted) { if (state.isDeleted) onDismiss() }

    // Mutually exclusive rather than stacked: only one Dialog window is ever active at a time.
    if (state.isDeleteConfirmOpen) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete item?") },
            text = { Text("\"${state.name}\" will be removed from the registry.") },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Edit item") },
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
                    Spacer(Modifier.height(8.dp))
                    ItemFormFields(state = state, viewModel = viewModel)
                    Spacer(Modifier.height(8.dp))

                    Text("Status", style = MaterialTheme.typography.labelMedium)
                    Row {
                        Status.entries.forEach { status ->
                            FilterChip(
                                selected = state.status == status,
                                onClick = { viewModel.onStatusChange(status) },
                                label = { Text(status.wireValue) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    TextButton(onClick = viewModel::requestDelete) { Text("Delete") }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::save) { Text("Save") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
