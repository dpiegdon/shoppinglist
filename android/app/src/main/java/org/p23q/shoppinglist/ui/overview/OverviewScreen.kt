package org.p23q.shoppinglist.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.ExpenseMath
import org.p23q.shoppinglist.data.ListKind
import org.p23q.shoppinglist.ui.AddFab
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.ui.SyncStatusBar
import org.p23q.shoppinglist.ui.rememberTickingNowMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    onOpenList: (listId: String) -> Unit,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        floatingActionButton = {
            // The same Add button as every list (T-168), in the primary colours, not Material's
            // paler default (T-174).
            AddFab(onClick = viewModel::openCreateDialog, contentDescription = stringResource(R.string.overview_new_list))
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
                                    val summary = state.expenseSummaries[list.id]
                                    if (summary != null) {
                                        // What has been spent, and where this account stands —
                                        // an expenses list has no open items to count (T-154).
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "${ExpenseMath.fromCents(summary.totalCents)} ${summary.currency}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            summary.myBalanceCents?.let { balance ->
                                                Text(
                                                    text = "${ExpenseMath.fromCents(balance)} ${summary.currency}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (balance < 0) {
                                                        MaterialTheme.colorScheme.error
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    },
                                                )
                                            }
                                        }
                                    }
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
                    listOf(ListKind.SHOPPING, ListKind.CHECKLIST, ListKind.EXPENSES).forEach { kind ->
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
                        when (state.newListKind) {
                            ListKind.CHECKLIST -> stringResource(R.string.overview_kind_checklist)
                            ListKind.EXPENSES -> stringResource(R.string.overview_kind_expenses)
                            else -> stringResource(R.string.overview_kind_shopping)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Free text, not a picker: the server takes any label, so a group that settles
                    // in pizza slices can say so. Fixed once the list exists.
                    if (ListKind.isExpenses(state.newListKind)) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.newListCurrency,
                            onValueChange = viewModel::onNewListCurrencyChange,
                            label = { Text(stringResource(R.string.expense_currency)) },
                            singleLine = true,
                        )
                        Text(
                            stringResource(R.string.overview_currency_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
