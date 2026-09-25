package org.p23q.shoppinglist.ui.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.DragReorderState
import org.p23q.shoppinglist.ui.LocalAreaNote
import org.p23q.shoppinglist.ui.accountName
import org.p23q.shoppinglist.ui.asString
import org.p23q.shoppinglist.ui.attentionText
import org.p23q.shoppinglist.ui.dragReorderHandle
import org.p23q.shoppinglist.ui.dragReorderItem
import org.p23q.shoppinglist.ui.rememberDragReorderState
import org.p23q.shoppinglist.ui.rememberTickingNowMs
import org.p23q.shoppinglist.ui.syncRecencyText

/**
 * Every account on this phone (T-292), in the order the overview shows them: who it is, where, in
 * what state, and how its own sync is doing. A row opens that account's screen; a signed-out row
 * offers the sign-in right on it. There is no sign-out here: the server signs an account out, and
 * signing in again is the way back.
 */
@Composable
fun AccountsScreen(
    onAddAccount: () -> Unit,
    onOpenAccount: (accountId: String) -> Unit,
    onSignIn: (accountId: String) -> Unit,
    /** An outdated row's "Check for update": ask the servers for a newer app and offer it (T-304). */
    onCheckForUpdate: () -> Unit = {},
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val canAddLocal by viewModel.canAddLocal.collectAsStateWithLifecycle()
    val localNoteOpen by viewModel.localNoteOpen.collectAsStateWithLifecycle()
    val nowMs = rememberTickingNowMs()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Dragged by a handle as a list's categories are (T-307): the server accounts move among
        // themselves; the local area sorts last, has no handle, and nothing is dragged past it.
        val gapPx = with(LocalDensity.current) { 8.dp.toPx() }
        val reorder = rememberDragReorderState(
            keys = rows.map { it.account.id },
            onMove = { from, to ->
                val id = rows[from].account.id
                if (to < from) viewModel.moveUp(id) else viewModel.moveDown(id)
            },
            canMoveTo = { to -> rows.getOrNull(to)?.account?.isServer == true },
            gapPx = gapPx,
        )
        val lastServer = rows.indexOfLast { it.account.isServer }
        rows.forEachIndexed { index, row ->
            key(row.account.id) {
                AccountCard(
                    row = row,
                    nowMs = nowMs,
                    reorder = reorder,
                    canMoveUp = index > 0,
                    canMoveDown = index < lastServer,
                    onOpen = { onOpenAccount(row.account.id) },
                    onSignIn = { onSignIn(row.account.id) },
                    onCheckForUpdate = onCheckForUpdate,
                    onMoveUp = { viewModel.moveUp(row.account.id) },
                    onMoveDown = { viewModel.moveDown(row.account.id) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        OutlinedButton(onClick = onAddAccount, modifier = Modifier.fillMaxWidth().testTag("accounts-add")) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.accounts_add))
        }
        // One local area per phone (T-293): offered while there is none.
        if (canAddLocal) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.addLocal() }, modifier = Modifier.fillMaxWidth().testTag("accounts-add-local")) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.accounts_add_local))
            }
        }
    }

    if (localNoteOpen) LocalAreaNote(onDismiss = viewModel::dismissLocalNote)
}

@Composable
private fun AccountCard(
    row: AccountRow,
    nowMs: Long,
    reorder: DragReorderState<String>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onSignIn: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val account = row.account
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().dragReorderItem(reorder, account.id).testTag("account-row-${account.id}"),
        colors = if (reorder.isDragging(account.id)) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                // Two lines, email then the full server URL, rather than the stored label: two
                // accounts on one host (prod and stage) differ only in the path.
                Text(accountName(account), style = MaterialTheme.typography.titleMedium)
                account.serverUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(4.dp))
                when (row.status) {
                    AccountStatus.SIGNED_IN -> {
                        Text(stringResource(R.string.accounts_state_signed_in), style = MaterialTheme.typography.bodyMedium)
                        SyncFigures(row, nowMs)
                    }
                    AccountStatus.SIGNED_OUT -> {
                        TextButton(onClick = onSignIn, modifier = Modifier.testTag("account-sign-in-${account.id}")) {
                            Text(stringResource(R.string.accounts_state_signed_out))
                        }
                        SyncFigures(row, nowMs)
                    }
                    AccountStatus.OUTDATED -> {
                        Text(
                            stringResource(R.string.accounts_state_outdated),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(R.string.accounts_outdated_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onCheckForUpdate, modifier = Modifier.testTag("account-check-update-${account.id}")) {
                            Text(stringResource(R.string.update_check_action))
                        }
                    }
                    // Named "On this phone" above; what that means, here.
                    AccountStatus.LOCAL ->
                        Text(stringResource(R.string.accounts_local_help), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (account.isServer) {
                // The arrows' moves stay offered to accessibility services, as actions on the handle.
                val up = stringResource(R.string.accounts_move_up)
                val down = stringResource(R.string.accounts_move_down)
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(R.string.listprops_reorder_category, accountName(account)),
                    modifier = Modifier
                        .dragReorderHandle(reorder, account.id)
                        .padding(12.dp)
                        .semantics {
                            customActions = listOfNotNull(
                                CustomAccessibilityAction(up) { onMoveUp(); true }.takeIf { canMoveUp },
                                CustomAccessibilityAction(down) { onMoveDown(); true }.takeIf { canMoveDown },
                            )
                        }
                        .testTag("account-handle-${account.id}"),
                )
            }
        }
    }
}

/** The account's own sync figures: recency, pending and, when the server quarantined rows, how many. */
@Composable
private fun SyncFigures(row: AccountRow, nowMs: Long) {
    Text(
        syncRecencyText(row.sync, nowMs).asString(),
        style = MaterialTheme.typography.bodySmall,
        color = if (row.sync.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (row.sync.blockedCount > 0) {
        Text(
            attentionText(row.sync.blockedCount).asString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
