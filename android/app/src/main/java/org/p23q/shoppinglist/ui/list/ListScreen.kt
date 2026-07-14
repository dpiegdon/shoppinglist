package org.p23q.shoppinglist.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.ui.SyncStatusMarker
import org.p23q.shoppinglist.data.db.Status

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onAddItem: () -> Unit,
    onEditItem: (itemId: String) -> Unit,
    onOpenRegistry: () -> Unit = {},
    onOpenListProps: () -> Unit = {},
    viewModel: ListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.undoItemId) {
        val name = state.undoItemName
        val itemId = state.undoItemId
        if (itemId != null && name != null) {
            val result = snackbarHostState.showSnackbar(
                message = "$name checked",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoCheckOff()
            } else {
                viewModel.dismissUndo()
            }
        }
    }

    LaunchedEffect(state.clearedCheckedIds) {
        val count = state.clearedCheckedIds.size
        if (count > 0) {
            val result = snackbarHostState.showSnackbar(
                message = if (count == 1) "1 item cleared" else "$count items cleared",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoClearChecked()
            } else {
                viewModel.dismissClearUndo()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // This screen already sits inside AppDrawerScaffold's Scaffold (which insets for the top
        // bar); without this, this inner Scaffold re-applies the status-bar inset and the controls
        // sit a status-bar-height too low, leaving empty space up top.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // Slim controls row: show-checked toggle-button, plus "Clear checked" beside it when
            // there are checked items; the registry and list-settings actions sit on the right (T-35).
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = state.showChecked,
                        onClick = { viewModel.toggleShowChecked() },
                        label = { Text("Show checked") },
                    )
                    if (state.checkedCount > 0) {
                        TextButton(
                            onClick = { viewModel.clearChecked() },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Clear checked (${state.checkedCount})")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Folded into this row instead of its own line (T-63): a quiet dot rather than a
                    // full "Synced 5 min ago" sentence; the sentence itself is still there as the
                    // content description for TalkBack. The loud attention banner is Overview's job.
                    SyncStatusMarker(state = state.sync, nowMs = System.currentTimeMillis())
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onOpenRegistry, modifier = Modifier.size(40.dp)) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = "Registry")
                    }
                    IconButton(onClick = onOpenListProps, modifier = Modifier.size(40.dp)) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "List properties")
                    }
                }
            }
            Button(
                onClick = onAddItem,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add item")
            }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    state.groups.forEachIndexed { index, group ->
                    item(key = "header-${group.category ?: "—"}") {
                        // A thin divider between categories (not above the first) makes groups easy to
                        // tell apart; the header itself gets a colored accent.
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        Text(
                            text = group.category ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                        )
                    }
                    items(group.items, key = { it.id }) { item ->
                        ItemRow(
                            item = item,
                            defaultCurrency = state.defaultCurrency,
                            onToggle = {
                                if (item.status.value == Status.CHECKED.wireValue) {
                                    viewModel.uncheck(item.id)
                                } else {
                                    viewModel.checkOff(item.id)
                                }
                            },
                            onEdit = { onEditItem(item.id) },
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: ItemEntity,
    defaultCurrency: String?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val isChecked = item.status.value == Status.CHECKED.wireValue
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name.value,
                    style = if (isChecked) {
                        // Theme-aware (was a hardcoded Color.Red with poor dark-theme contrast — T-40).
                        // The strike itself now spans the whole row (below), not just this text.
                        MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.error)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                )
                // Quantity is the thing you need in-store ("2l milk"), so show it alongside the price.
                val quantity = item.quantity.value?.takeIf { it.isNotBlank() }
                val detail = listOfNotNull(quantity, formatPrice(item, defaultCurrency)).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(text = detail, style = MaterialTheme.typography.bodySmall)
                }
                item.note.value?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit ${item.name.value}")
            }
        }
        // A per-word LineThrough only crossed the name, leaving quantity/note/icon untouched; one
        // line across the whole row reads more clearly as "done" (T-63).
        if (isChecked) {
            HorizontalDivider(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 16.dp)
                    .testTag("checked-item-strike"),
                thickness = 1.5.dp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
