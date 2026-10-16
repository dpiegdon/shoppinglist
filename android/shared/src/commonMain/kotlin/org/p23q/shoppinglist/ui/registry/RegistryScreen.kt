package org.p23q.shoppinglist.ui.registry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import org.koin.compose.viewmodel.koinViewModel
import org.p23q.shoppinglist.core.db.ItemEntity
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.data.db.label
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.action_undo
import org.p23q.shoppinglist.resources.registry_delete_item
import org.p23q.shoppinglist.resources.registry_empty
import org.p23q.shoppinglist.resources.registry_item_deleted
import org.p23q.shoppinglist.resources.registry_search
import org.p23q.shoppinglist.ui.EmptyState
import org.p23q.shoppinglist.ui.appLocale
import org.p23q.shoppinglist.ui.formatString
import org.p23q.shoppinglist.ui.stringResource

@Composable
fun RegistryScreen(
    onEditItem: (itemId: String) -> Unit,
    viewModel: RegistryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val undoLabel = stringResource(Res.string.action_undo)
    val deletedTemplate = stringResource(Res.string.registry_item_deleted)
    val locale = appLocale()
    LaunchedEffect(state.undoItemId) {
        val name = state.undoItemName
        val itemId = state.undoItemId
        if (itemId != null && name != null) {
            // Leaving the screen while the snackbar shows cancels this effect (T-346). The view
            // model outlives the screen, so the offer is cleared here, or coming back would show
            // it again for an item checked long ago.
            val result = try {
                snackbarHostState.showSnackbar(
                    // Hoisted above the effect: this body is a coroutine, not a composition.
                    message = formatString(locale, deletedTemplate, name),
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Short,
                )
            } catch (cancelled: CancellationException) {
                viewModel.dismissUndo(itemId)
                throw cancelled
            }
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            } else {
                viewModel.dismissUndo()
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(Res.string.registry_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Said, not left blank, when nothing matches, as the web does (T-342).
                if (state.items.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(stringResource(Res.string.registry_empty))
                    }
                }
                items(state.items, key = { it.localId }) { item ->
                    RegistryRow(
                        item = item,
                        onClick = { onEditItem(item.localId) },
                        onDelete = { viewModel.deleteItem(item.localId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RegistryRow(item: ItemEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = item.name.value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // Muted text, as the web shows it: a chip looked like a control of its own, yet only opened
        // the editor the row already opens (T-342).
        Text(
            text = stringResource(Status.fromWireValue(item.status.value).label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onDelete) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(Res.string.registry_delete_item, item.name.value))
        }
    }
}
