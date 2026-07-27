package org.p23q.shoppinglist.ui.listprops

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import org.p23q.shoppinglist.data.ListKind
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.ui.asString
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.UiText

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

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.listprops_list_name), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.saveName() }) { Text(stringResource(R.string.action_save)) }
        }
        Spacer(Modifier.height(16.dp))

        // Convert between shopping list and checklist (T-110) — non-destructive, so it's a plain
        // switch rather than a guarded action.
        Text(stringResource(R.string.listprops_type), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${ListKind.icon(state.kind)}  ${ListKind.label(state.kind)}")
                Text(
                    if (state.kind == ListKind.CHECKLIST) {
                        stringResource(R.string.listprops_kind_checklist)
                    } else {
                        stringResource(R.string.listprops_kind_shopping)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.kind == ListKind.CHECKLIST,
                onCheckedChange = { checked ->
                    viewModel.setKind(if (checked) ListKind.CHECKLIST else ListKind.SHOPPING)
                },
            )
        }
        Text(
            stringResource(R.string.listprops_kind_switch_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.listprops_categories), style = MaterialTheme.typography.titleMedium)
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
        TextButton(onClick = { viewModel.saveCategoryOrder() }) { Text(stringResource(R.string.listprops_save_order)) }
        Spacer(Modifier.height(16.dp))

        // Relocated here from the list screen (T-75), where it was too easy to tap by accident: move
        // every checked item to backlog. A proper filled red button (T-82), matching the web
        // version's btn-danger; only shown when there's something to clear.
        if (state.checkedCount > 0) {
            Button(
                onClick = { viewModel.clearChecked() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.listprops_clear_checked, state.checkedCount))
            }
            Spacer(Modifier.height(16.dp))
        }

        // Free-text, not-regularly-needed info (T-62) — lives only here, not on the list/overview screens.
        Text(stringResource(R.string.listprops_notes), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.notes,
            onValueChange = viewModel::onNotesChange,
            placeholder = { Text(stringResource(R.string.listprops_notes_placeholder)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { viewModel.saveNotes() }) { Text(stringResource(R.string.listprops_save_notes)) }
        Spacer(Modifier.height(16.dp))

        // Per-list collaborator-change notification mute (T-65); the global switch is in Settings.
        Text(stringResource(R.string.listprops_notifications), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.listprops_notify_changes), modifier = Modifier.weight(1f))
            Switch(
                checked = state.notificationsEnabledForList,
                onCheckedChange = { viewModel.setListNotificationsEnabled(it) },
            )
        }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.listprops_shared_with), style = MaterialTheme.typography.titleMedium)
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
                Text("${invite.invitedEmail} (pending)")
                TextButton(onClick = { viewModel.revokeInvite(invite.id) }) { Text(stringResource(R.string.action_revoke)) }
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
            TextButton(onClick = { viewModel.sendInvite() }) { Text(stringResource(R.string.action_invite)) }
        }
        state.errorMessage?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))

        // Client-side snapshot copy (T-63): a private, single-owner list with its own history.
        TextButton(onClick = viewModel::duplicateList) { Text(stringResource(R.string.action_duplicate)) }
        Spacer(Modifier.height(8.dp))

        // stringResource(R.string.listprops_leave_list) (was stringResource(R.string.action_unsubscribe), T-112): red, matching the Clear-checked danger action.
        Button(
            onClick = viewModel::requestLeave,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.listprops_leave_list)) }
    }

    if (state.isLeaveConfirmOpen) {
        AlertDialog(
            onDismissRequest = viewModel::cancelLeave,
            title = { Text(stringResource(R.string.listprops_leave_confirm_title)) },
            // Through UiText, not stringResource directly, so the list name gets bidi-isolated
            // (T-126) — this is VISIBLE text with the name embedded mid-sentence in quotes. The
            // contentDescription sites elsewhere are spoken by TalkBack, where reordering does not
            // arise, so they stay on plain stringResource.
            text = { Text(UiText.res(R.string.listprops_leave_confirm_body, state.name).asString()) },
            confirmButton = { TextButton(onClick = viewModel::confirmLeave) { Text(stringResource(R.string.action_leave)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelLeave) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Real drag-reorder (T-30) replacing the old up/down buttons. Dragging the handle starts immediately
 * (no long-press) and the dragged row follows the finger (translationY + raised zIndex); once it has
 * travelled one row-height it swaps with its neighbour via the existing moveCategoryUp/Down edits
 * (persistence unchanged) and the offset is rebased by a row so the motion stays continuous. Rows are
 * keyed by category and the gesture reads the category's *current* index live (rememberUpdatedState),
 * so the handle keeps following its item across swaps.
 */
@Composable
private fun CategoryOrderList(
    categories: List<String>,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { 44.dp.toPx() }
    var draggingCategory by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
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
                        TextButton(onClick = {
                            onRename(currentCategories.indexOf(category), draftName)
                            editingCategory = null
                        }) { Text(stringResource(R.string.action_save)) }
                        TextButton(onClick = { editingCategory = null }) { Text(stringResource(R.string.action_cancel)) }
                    }
                } else {
                    val dragging = draggingCategory == category
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
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
                            modifier = Modifier.pointerInput(category) {
                                detectDragGestures(
                                    onDragStart = { draggingCategory = category; dragOffset = 0f },
                                    onDragEnd = { draggingCategory = null; dragOffset = 0f },
                                    onDragCancel = { draggingCategory = null; dragOffset = 0f },
                                ) { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.y
                                    val idx = currentCategories.indexOf(category)
                                    if (idx < 0) return@detectDragGestures
                                    if (dragOffset <= -rowHeightPx && idx > 0) {
                                        onMoveUp(idx)
                                        dragOffset += rowHeightPx
                                    } else if (dragOffset >= rowHeightPx && idx < currentCategories.size - 1) {
                                        onMoveDown(idx)
                                        dragOffset -= rowHeightPx
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
