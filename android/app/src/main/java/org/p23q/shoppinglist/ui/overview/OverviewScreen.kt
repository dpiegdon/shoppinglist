package org.p23q.shoppinglist.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.data.ListKind
import org.p23q.shoppinglist.ui.SyncStatusBar
import org.p23q.shoppinglist.ui.rememberTickingNowMs
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    onOpenList: (listId: String) -> Unit,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openCreateDialog) {
                Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.overview_new_list))
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            SyncStatusBar(
                state = state.sync,
                nowMs = rememberTickingNowMs(),
                onAttentionClick = { state.attentionListId?.let(onOpenList) },
            )
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.lists.isEmpty()) {
                    // Scrollable so the pull gesture still fires with no lists to scroll.
                    Box(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.overview_no_lists))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        items(state.lists, key = { it.id }) { list ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        viewModel.openList(list.id)
                                        onOpenList(list.id)
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = ListKind.icon(list.kind.value),
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    Text(text = list.name.value, modifier = Modifier.weight(1f))
                                    val openCount = state.openCounts[list.id] ?: 0
                                    if (openCount > 0) {
                                        // At-a-glance "is a trip pending" count of open items (T-42).
                                        Text(
                                            text = openCount.toString(),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.isCreateDialogOpen) {
        LocalizedAlertDialog(
            onDismissRequest = viewModel::dismissCreateDialog,
            title = { Text(stringResource(R.string.overview_new_list)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.newListName,
                        onValueChange = viewModel::onNewListNameChange,
                        label = { Text(stringResource(R.string.overview_list_name)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Kind is chosen up front (T-110) but isn't permanent — list properties can
                    // convert it later, and converting never touches item data.
                    Text(stringResource(R.string.overview_type), style = MaterialTheme.typography.labelMedium)
                    listOf(ListKind.SHOPPING, ListKind.CHECKLIST).forEach { kind ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = state.newListKind == kind,
                                    onClick = { viewModel.onNewListKindChange(kind) },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.newListKind == kind,
                                onClick = { viewModel.onNewListKindChange(kind) },
                            )
                            Text("${ListKind.icon(kind)}  ${stringResource(ListKind.label(kind))}")
                        }
                    }
                    Text(
                        if (state.newListKind == ListKind.CHECKLIST) {
                            stringResource(R.string.overview_kind_checklist)
                        } else {
                            stringResource(R.string.overview_kind_shopping)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::createList) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCreateDialog) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
