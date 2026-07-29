package org.p23q.shoppinglist.ui.item

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.asString

/** Notes (Add-item dialog): name suggestions from the registry; picking one reuses it, else creates new. */
@Composable
fun AddItemDialog(
    listId: String,
    onDismiss: () -> Unit,
    viewModel: ItemFormViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(listId) { viewModel.startAdd(listId) }
    LaunchedEffect(state.isSaved) { if (state.isSaved) onDismiss() }
    // After a stringResource(R.string.item_add_another) the form resets and bumps this signal; put the cursor back in Name.
    LaunchedEffect(state.focusNameSignal) {
        if (state.focusNameSignal > 0) runCatching { nameFocusRequester.requestFocus() }
    }

    LocalizedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.item_add)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text(stringResource(R.string.item_name)) },
                    singleLine = true,
                    isError = state.nameError != null,
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocusRequester),
                )
                state.nameError?.let { error ->
                    Text(text = error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
        confirmButton = {
            Row {
                TextButton(onClick = viewModel::saveAndAddAnother) { Text(stringResource(R.string.item_add_another)) }
                TextButton(onClick = viewModel::save) { Text(stringResource(R.string.action_add)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
