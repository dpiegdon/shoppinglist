package org.p23q.shoppinglist.ui.listprops

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.AppFormat
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.data.label
import org.p23q.shoppinglist.ui.CompactButtonPadding
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.ui.SectionCard
import org.p23q.shoppinglist.ui.UiText
import org.p23q.shoppinglist.ui.accountLineText
import org.p23q.shoppinglist.ui.appLocale
import org.p23q.shoppinglist.ui.asString
import org.p23q.shoppinglist.ui.dangerButtonColors
import org.p23q.shoppinglist.ui.dragReorderHandle
import org.p23q.shoppinglist.ui.dragReorderItem
import org.p23q.shoppinglist.ui.rememberDragReorderState
import org.p23q.shoppinglist.ui.theme.TuppuButton
import org.p23q.shoppinglist.ui.theme.TuppuTextButton

@Composable
fun ListPropsScreen(
    onLeft: () -> Unit,
    onDuplicated: (listId: String) -> Unit,
    viewModel: ListPropsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadMembers() }
    LaunchedEffect(state.hasLeft) { if (state.hasLeft) onLeft() }
    LaunchedEffect(state.duplicatedListId) { state.duplicatedListId?.let(onDuplicated) }
    // A copy's name ends in "(Copy)" in the app's language, the copier's (T-302).
    val copySuffix = stringResource(R.string.list_copy_suffix)
    // Hoisted above the effect: its body is a coroutine, not a composition.
    val shareInviteTitle = stringResource(R.string.listprops_share_invite)
    LaunchedEffect(state.inviteShareUrl) {
        val url = state.inviteShareUrl
        if (url != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            context.startActivity(Intent.createChooser(intent, shareInviteTitle))
            viewModel.consumeShareUrl()
        }
    }

    // The sections sit in cards, as on the web and in Settings, in one order on both clients
    // (T-337): the list itself, its categories, notes, who it is shared with, its notifications,
    // closing a ledger, the actions, and last, in red, leaving it.
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Someone who has agreed to close an expense list changes nothing on it (T-193).
        val lockedByVote = ListKind.isExpenses(state.kind) &&
            state.closedAt == null &&
            state.myAccountId in state.closeVotes
        if (lockedByVote) {
            Text(
                stringResource(R.string.api_error_voted_to_close),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val isExpenses = ListKind.isExpenses(state.kind)

        SectionCard(stringResource(R.string.listprops_list)) {
            Text(stringResource(R.string.listprops_list_name), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    singleLine = true,
                    enabled = !lockedByVote,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TuppuButton(onClick = { viewModel.saveName() }, enabled = !lockedByVote) {
                    Text(stringResource(R.string.action_save))
                }
            }
            Spacer(Modifier.height(16.dp))

            // Convert between shopping list and checklist (T-110) — non-destructive, so it's a plain
            // switch rather than a guarded action.
            Text(stringResource(R.string.listprops_type), style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${ListKind.icon(state.kind)}  ${stringResource(ListKind.label(state.kind))}")
                    Text(
                        when (state.kind) {
                            ListKind.CHECKLIST -> stringResource(R.string.listprops_kind_checklist)
                            ListKind.EXPENSES -> stringResource(R.string.listprops_kind_expenses)
                            else -> stringResource(R.string.listprops_kind_shopping)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isExpenses) {
                        Text(
                            stringResource(R.string.expense_currency_value, state.currency),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                // No switch for an expenses list: the server refuses to convert one in either
                // direction, because its items have a different shape entirely (T-151).
                if (!isExpenses) {
                    Switch(
                        checked = state.kind == ListKind.CHECKLIST,
                        onCheckedChange = { checked ->
                            viewModel.setKind(if (checked) ListKind.CHECKLIST else ListKind.SHOPPING)
                        },
                    )
                }
            }
            Text(
                stringResource(
                    if (isExpenses) R.string.listprops_kind_fixed else R.string.listprops_kind_switch_help,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Relocated here from the list screen (T-75), where it was too easy to tap by accident:
            // move every checked item to backlog. A proper filled red button (T-82), matching the
            // web version's btn-danger; only shown when there's something to clear. The last thing
            // in the List card, directly under the type (T-337).
            if (state.checkedCount > 0) {
                Spacer(Modifier.height(16.dp))
                TuppuButton(
                    onClick = { viewModel.clearChecked() },
                    colors = dangerButtonColors(),
                ) {
                    Text(stringResource(R.string.listprops_clear_checked, state.checkedCount))
                }
            }
        }

        if (!isExpenses) {
            SectionCard(stringResource(R.string.listprops_categories)) {
                Text(
                    stringResource(R.string.listprops_categories_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CategoryOrderList(
                    categories = state.categoryOrder,
                    onMoveUp = viewModel::moveCategoryUp,
                    onMoveDown = viewModel::moveCategoryDown,
                    onRename = { index, newName -> viewModel.renameCategory(index, newName) },
                )
                TuppuButton(onClick = { viewModel.saveCategoryOrder() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.listprops_save_order))
                }
            }
        }

        // Free-text, not-regularly-needed info (T-62) — lives only here, not on the list/overview screens.
        SectionCard(stringResource(R.string.listprops_notes)) {
            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::onNotesChange,
                placeholder = { Text(stringResource(R.string.listprops_notes_placeholder)) },
                minLines = 3,
                maxLines = 6,
                enabled = !lockedByVote,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TuppuButton(onClick = { viewModel.saveNotes() }, enabled = !lockedByVote, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.listprops_save_notes))
            }
        }

        // A list in the local area is nobody else's (T-293): no collaborators to hear from, no
        // roster, no invites. Nothing of this is drawn until the list's account is known.
        if (state.local == true) {
            SectionCard(stringResource(R.string.listprops_shared_with)) {
                Text(stringResource(R.string.listprops_member_you))
                Text(
                    stringResource(R.string.listprops_local_not_shared),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.local == false) {
            SharedWithSection(state, viewModel)
            NotificationsSection(state, viewModel)
        }
        state.errorMessage?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }

        // Closing an expenses list (T-158): unanimous, and the only way it can later be left. Directly
        // above Leave (T-169): the two are stages of one thing — agree to close, then leave. A
        // ledger has no Actions card between them.
        if (isExpenses) {
            SectionCard(stringResource(R.string.expense_closing)) {
                CloseVoteSection(state, viewModel)
            }
        }

        // Client-side snapshot copy (T-63): a private, single-owner list with its own history.
        if (!isExpenses) {
            SectionCard(stringResource(R.string.listprops_actions)) {
                TuppuButton(onClick = { viewModel.requestDuplicate(copySuffix) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_duplicate))
                }
            }
        }

        // Leaving (T-112), red like the web's: an open expenses list cannot be left (T-157) —
        // saying why beats a button that fails. A local list is deleted instead: it exists on this
        // phone alone (T-293).
        val leaveBlocked = isExpenses && state.closedAt == null
        val leaveLabel = stringResource(if (state.local == true) R.string.listprops_delete_list else R.string.listprops_leave_list)
        SectionCard(leaveLabel, danger = true) {
            TuppuButton(
                onClick = viewModel::requestLeave,
                enabled = !leaveBlocked,
                colors = dangerButtonColors(),
            ) { Text(leaveLabel) }
            if (leaveBlocked) {
                Text(
                    stringResource(R.string.listprops_leave_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (state.isLeaveConfirmOpen) {
        val local = state.local == true
        LocalizedAlertDialog(
            onDismissRequest = viewModel::cancelLeave,
            title = {
                Text(stringResource(if (local) R.string.listprops_delete_confirm_title else R.string.listprops_leave_confirm_title))
            },
            // Through UiText, not stringResource directly, so the list name gets bidi-isolated
            // (T-126) — this is VISIBLE text with the name embedded mid-sentence in quotes. The
            // contentDescription sites elsewhere are spoken by TalkBack, where reordering does not
            // arise, so they stay on plain stringResource.
            text = {
                Text(
                    UiText.res(
                        if (local) R.string.listprops_delete_confirm_body else R.string.listprops_leave_confirm_body,
                        state.name,
                    ).asString(),
                )
            },
            confirmButton = {
                TuppuTextButton(onClick = viewModel::confirmLeave) {
                    Text(stringResource(if (local) R.string.action_delete else R.string.action_leave))
                }
            },
            dismissButton = { TuppuTextButton(onClick = viewModel::cancelLeave) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // With several accounts, which one the copy goes to (T-294): the list's own first.
    if (state.copyTargets.isNotEmpty()) {
        CopyToDialog(state.copyTargets, onPick = { viewModel.duplicateList(copySuffix, it) }, onDismiss = viewModel::cancelCopy)
    }

    // Renaming a category onto another existing one merges them irreversibly (T-270): the web
    // already confirms this; Android used to do it silently on Save.
    state.pendingCategoryMerge?.let { pending ->
        LocalizedAlertDialog(
            onDismissRequest = viewModel::cancelCategoryMerge,
            title = { Text(UiText.res(R.string.listprops_merge_confirm_title, pending.targetName).asString()) },
            text = { Text(stringResource(R.string.listprops_merge_confirm_body)) },
            confirmButton = { TuppuTextButton(onClick = viewModel::confirmCategoryMerge) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TuppuTextButton(onClick = viewModel::cancelCategoryMerge) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The "Copy to" picker (T-294): one row per account, each named as the overview names it. */
@Composable
private fun CopyToDialog(targets: List<AccountEntity>, onPick: (accountId: String) -> Unit, onDismiss: () -> Unit) {
    LocalizedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.listprops_copy_to)) },
        text = {
            Column {
                targets.forEach { account ->
                    Text(
                        accountLineText(account),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(account.id) }
                            .padding(vertical = 12.dp)
                            .testTag(COPY_TARGET_TAG_PREFIX + account.id),
                    )
                }
            }
        },
        confirmButton = { TuppuTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

internal const val COPY_TARGET_TAG_PREFIX = "copy-target-"

/** Who a server list is shared with: the roster, pending invites and inviting someone (T-293: not a local list). */
@Composable
private fun SharedWithSection(state: ListPropsUiState, viewModel: ListPropsViewModel) {
    SectionCard(stringResource(R.string.listprops_shared_with)) {
        if (state.isMembersLoading) {
            CircularProgressIndicator()
        }
        state.membersError?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        state.members.forEach { member -> Text(member.email) }
        state.pendingInvites.forEach { invite ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.listprops_invite_pending, invite.invitedEmail))
                TuppuButton(
                    onClick = { viewModel.revokeInvite(invite.id) },
                    colors = dangerButtonColors(),
                    contentPadding = CompactButtonPadding,
                ) { Text(stringResource(R.string.action_revoke)) }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.inviteEmail,
                onValueChange = viewModel::onInviteEmailChange,
                label = { Text(stringResource(R.string.listprops_invite_by_email)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TuppuButton(onClick = { viewModel.sendInvite() }) { Text(stringResource(R.string.action_invite)) }
        }
    }
}

/**
 * The per-list collaborator-change notification mute (T-65); the global switch is in Settings.
 * Android only: the web shows no notifications. A local list has no collaborators (T-293).
 */
@Composable
private fun NotificationsSection(state: ListPropsUiState, viewModel: ListPropsViewModel) {
    SectionCard(stringResource(R.string.listprops_notifications)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.listprops_notify_changes), modifier = Modifier.weight(1f))
            Switch(
                checked = state.notificationsEnabledForList,
                onCheckedChange = { viewModel.setListNotificationsEnabled(it) },
            )
        }
    }
}

/** Agreeing to close an expenses list (T-158), or the day it closed. */
@Composable
private fun CloseVoteSection(state: ListPropsUiState, viewModel: ListPropsViewModel) {
    Text(
        stringResource(R.string.expense_closing_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val closedAt = state.closedAt
    if (closedAt != null) {
        Text(
            stringResource(
                R.string.expense_closed_on,
                AppFormat.day(closedAt, appLocale()),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.expense_agree_count, state.closeVotes.size, state.memberCount),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TuppuButton(onClick = { viewModel.toggleCloseVote() }, enabled = !state.isVoting) {
                Text(
                    stringResource(
                        if (state.myAccountId in state.closeVotes) {
                            R.string.expense_withdraw_vote
                        } else {
                            R.string.expense_agree_to_close
                        },
                    ),
                )
            }
        }
    }
}

/** The categories in order, each renamable and dragged by its handle to reorder (T-30, [rememberDragReorderState]). */
@Composable
private fun CategoryOrderList(
    categories: List<String>,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { 44.dp.toPx() }
    // Moves go through the existing moveCategoryUp/Down edits, so persistence is unchanged.
    val reorder = rememberDragReorderState(
        keys = categories,
        onMove = { from, to -> if (to < from) onMoveUp(from) else onMoveDown(from) },
        fixedStepPx = rowHeightPx,
    )
    var editingCategory by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    val currentCategories by rememberUpdatedState(categories)

    Column {
        categories.forEach { category ->
            key(category) {
                if (editingCategory == category) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { draftName = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TuppuButton(
                            onClick = {
                                onRename(currentCategories.indexOf(category), draftName)
                                editingCategory = null
                            },
                            contentPadding = CompactButtonPadding,
                        ) { Text(stringResource(R.string.action_save)) }
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = { editingCategory = null }, contentPadding = CompactButtonPadding) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                } else {
                    val dragging = reorder.isDragging(category)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .dragReorderItem(reorder, category)
                            .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(category, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editingCategory = category; draftName = category }) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = stringResource(R.string.listprops_rename_category, category))
                        }
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = stringResource(R.string.listprops_reorder_category, category),
                            modifier = Modifier.dragReorderHandle(reorder, category),
                        )
                    }
                }
            }
        }
    }
}

