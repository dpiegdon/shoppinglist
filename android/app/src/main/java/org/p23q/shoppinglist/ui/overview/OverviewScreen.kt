package org.p23q.shoppinglist.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.AppFormat
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.api.InviteForMeDto
import org.p23q.shoppinglist.data.label
import org.p23q.shoppinglist.ui.AddFab
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.ui.SyncStatusBar
import org.p23q.shoppinglist.ui.appLocale
import org.p23q.shoppinglist.ui.asString
import org.p23q.shoppinglist.ui.expense.balanceColor
import org.p23q.shoppinglist.ui.rememberTickingNowMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    onOpenList: (listId: String) -> Unit,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowMs = rememberTickingNowMs()

    // A Join that went through opens the list, as redeeming a pasted link does (T-233).
    LaunchedEffect(state.joinedListId) {
        state.joinedListId?.let { listId ->
            viewModel.joinedListOpened()
            onOpenList(listId)
        }
    }

    Scaffold(
        floatingActionButton = {
            // The same Add button as every list (T-168), in the primary colours, not Material's
            // paler default (T-174).
            AddFab(onClick = viewModel::openCreateDialog, contentDescription = stringResource(R.string.overview_new_list))
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // The recency line moved into the top bar with the status dot (T-178); what stays here is
            // the banner for rows that failed to sync, which needs the room and the tap target.
            SyncStatusBar(
                state = state.sync,
                nowMs = nowMs,
                onAttentionClick = { state.attentionListId?.let(onOpenList) },
                showRecency = false,
            )
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                val openInvites = state.invites.filter { it.id !in state.ignoredInviteIds }
                val shelvedInvites = state.invites.filter { it.id in state.ignoredInviteIds }
                if (state.lists.isEmpty() && state.invites.isEmpty()) {
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
                        if (state.lists.isEmpty()) {
                            // Only invites to show: say the lists are empty where they would be.
                            item(key = "no-lists") {
                                Text(
                                    stringResource(R.string.overview_no_lists),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(state.lists, key = { it.localId }) { list ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        viewModel.openList(list.localId)
                                        onOpenList(list.localId)
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
                                    val summary = state.expenseSummaries[list.localId]
                                    if (summary != null) {
                                        // What has been spent, and where this account stands —
                                        // an expenses list has no open items to count (T-154).
                                        Column(horizontalAlignment = Alignment.End) {
                                            val locale = appLocale()
                                            val total = AppFormat.money(summary.totalCents, summary.currency, locale)
                                            Text(
                                                // "Closed · total", as the web writes it (T-181).
                                                text = if (summary.closed) {
                                                    "${stringResource(R.string.expense_closed)} · $total"
                                                } else {
                                                    total
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            summary.myBalanceCents?.let { balance ->
                                                Text(
                                                    text = AppFormat.signedMoney(balance, summary.currency, locale),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    // Square is grey, as everywhere else (T-182).
                                                    color = balanceColor(balance),
                                                )
                                            }
                                        }
                                    }
                                    val openCount = state.openCounts[list.localId] ?: 0
                                    if (openCount > 0) {
                                        // Room between an expense list's total/balance and its count
                                        // (T-191), which otherwise sat right against them.
                                        if (summary != null) Spacer(Modifier.width(12.dp))
                                        // At-a-glance "is a trip pending" count of open items (T-42);
                                        // on an expense list, its number of expenses.
                                        Text(
                                            text = openCount.toString(),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        // Invites waiting for this account (T-233), below the lists so what you have
                        // comes first. Ignoring is this device's choice alone: the card moves to the
                        // greyed section at the very bottom, where Join is still offered.
                        if (openInvites.isNotEmpty()) {
                            item(key = "invites-heading") {
                                SectionHeading(stringResource(R.string.overview_invites))
                            }
                            items(openInvites, key = { "invite-" + it.id }) { invite ->
                                InviteCard(
                                    invite = invite,
                                    nowMs = nowMs,
                                    ignored = false,
                                    busy = state.joiningInviteId != null,
                                    onJoin = { viewModel.joinInvite(invite) },
                                    onIgnore = { viewModel.ignoreInvite(invite.id) },
                                )
                            }
                        }
                        state.inviteError?.let { error ->
                            item(key = "invite-error") {
                                Text(
                                    error.asString(),
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        if (shelvedInvites.isNotEmpty()) {
                            item(key = "ignored-heading") {
                                SectionHeading(stringResource(R.string.overview_invites_ignored), muted = true)
                            }
                            items(shelvedInvites, key = { "ignored-" + it.id }) { invite ->
                                InviteCard(
                                    invite = invite,
                                    nowMs = nowMs,
                                    ignored = true,
                                    busy = state.joiningInviteId != null,
                                    onJoin = { viewModel.joinInvite(invite) },
                                    onIgnore = {},
                                )
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

@Composable
private fun SectionHeading(text: String, muted: Boolean = false) {
    Text(
        text,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

/** One invite on the overview (T-233): kind, list name, who invited and how long it stands, then Ignore and Join. */
@Composable
private fun InviteCard(
    invite: InviteForMeDto,
    nowMs: Long,
    ignored: Boolean,
    busy: Boolean,
    onJoin: () -> Unit,
    onIgnore: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            // Greyed once ignored, as the web does it; the card otherwise reads the same.
            .alpha(if (ignored) 0.6f else 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = ListKind.icon(invite.listKind), modifier = Modifier.padding(end = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = invite.listName)
                Text(
                    // "From AL · Expires in 5 d", as the web writes it.
                    text = stringResource(R.string.overview_invite_from, invite.invitedByInitials) +
                        " · " + formatExpiresIn(invite.expiresAt, nowMs).asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!ignored) {
                TextButton(onClick = onIgnore, enabled = !busy) { Text(stringResource(R.string.action_ignore)) }
            }
            TextButton(onClick = onJoin, enabled = !busy) { Text(stringResource(R.string.action_join)) }
        }
    }
}
