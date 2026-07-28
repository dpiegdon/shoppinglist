package org.p23q.shoppinglist.ui.item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.data.db.Status
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.asString

/** Notes (List view): the row edit icon / a long-press opens this — every field including name,
 *  plus delete. Full-screen (T-80) rather than a floating AlertDialog: no tap-outside-to-cancel
 *  (edits aren't lost by an accidental scrim tap), and a fixed action bar that stays above the
 *  soft keyboard while the field area scrolls underneath it. */
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

    // Mutually exclusive rather than stacked: only one dialog window is ever active at a time.
    if (state.isDeleteConfirmOpen) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.item_delete_title)) },
            text = { Text("\"${state.name}\" will be removed from the registry.") },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.action_cancel)) } },
        )
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            // Full-screen and no dismiss-on-click-outside (T-80); back-press still cancels.
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        ) {
            // A plain Compose Dialog window fits system windows and reports the IME inset as 0, so
            // safeDrawingPadding() couldn't lift the action bar above the keyboard. Opt this dialog's
            // window out of decor-fits-system-windows so the keyboard becomes a real inset (T-80).
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { dialogWindow?.let { WindowCompat.setDecorFitsSystemWindows(it, false) } }

            Surface(modifier = Modifier.fillMaxSize()) {
                // safeDrawingPadding on the outer column insets for the status bar (top) and the
                // nav bar / keyboard (bottom, whichever is larger) — so the fixed action bar below
                // rides up to sit just above the keyboard when it's open.
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                        Text(
                            stringResource(R.string.item_edit),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                    }
                    HorizontalDivider()

                    // Scrollable field area filling the gap between the fixed header and action bar.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = viewModel::onNameChange,
                            label = { Text(stringResource(R.string.item_name)) },
                            singleLine = true,
                            isError = state.nameError != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        state.nameError?.let { error ->
                            Text(text = error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        ItemFormFields(state = state, viewModel = viewModel)
                        Spacer(Modifier.height(8.dp))

                        Text(stringResource(R.string.item_status), style = MaterialTheme.typography.labelMedium)
                        Row {
                            Status.entries.forEach { status ->
                                FilterChip(
                                    selected = state.status == status,
                                    onClick = { viewModel.onStatusChange(status) },
                                    // The LABEL, not the wire value (T-124). This rendered the raw
                                    // identifier — "backlog", "todo", "checked" — which is exactly
                                    // what Status's docstring says never to surface, and would have
                                    // stayed untranslated English in every language.
                                    label = { Text(stringResource(status.label)) },
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                        }
                        if (state.status == Status.BACKLOG) {
                            // The gloss sits beside the control, not inside the label, so it never
                            // reaches the space-constrained Registry chip (T-124).
                            Text(
                                text = stringResource(R.string.status_backlog_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        TextButton(onClick = viewModel::requestDelete) { Text(stringResource(R.string.action_delete)) }
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = viewModel::save) { Text(stringResource(R.string.action_save)) }
                    }
                }
            }
        }
    }
}
