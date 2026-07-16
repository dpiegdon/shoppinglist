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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    LaunchedEffect(state.inviteShareUrl) {
        val url = state.inviteShareUrl
        if (url != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            context.startActivity(Intent.createChooser(intent, "Share invite"))
            viewModel.consumeShareUrl()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("List name", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.saveName() }) { Text("Save") }
        }
        Spacer(Modifier.height(16.dp))

        Text("Category order", style = MaterialTheme.typography.titleMedium)
        Text(
            "Drag the handle to reorder",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CategoryOrderList(
            categories = state.categoryOrder,
            onMoveUp = viewModel::moveCategoryUp,
            onMoveDown = viewModel::moveCategoryDown,
        )
        TextButton(onClick = { viewModel.saveCategoryOrder() }) { Text("Save order") }
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
                Text("Clear checked (${state.checkedCount})")
            }
            Spacer(Modifier.height(16.dp))
        }

        // Free-text, not-regularly-needed info (T-62) — lives only here, not on the list/overview screens.
        Text("Notes", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.notes,
            onValueChange = viewModel::onNotesChange,
            placeholder = { Text("Gate code, store hours, anything worth remembering…") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { viewModel.saveNotes() }) { Text("Save notes") }
        Spacer(Modifier.height(16.dp))

        // Per-list collaborator-change notification mute (T-65); the global switch is in Settings.
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Notify about changes to this list", modifier = Modifier.weight(1f))
            Switch(
                checked = state.notificationsEnabledForList,
                onCheckedChange = { viewModel.setListNotificationsEnabled(it) },
            )
        }
        Spacer(Modifier.height(16.dp))

        Text("Shared with", style = MaterialTheme.typography.titleMedium)
        if (state.isMembersLoading) {
            CircularProgressIndicator()
        }
        state.membersError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.members.forEach { member -> Text(member.email) }
        state.pendingInvites.forEach { invite ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${invite.invitedEmail} (pending)")
                TextButton(onClick = { viewModel.revokeInvite(invite.id) }) { Text("Revoke") }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.inviteEmail,
                onValueChange = viewModel::onInviteEmailChange,
                label = { Text("Invite by email") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.sendInvite() }) { Text("Invite") }
        }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))

        // Client-side snapshot copy (T-63): a private, single-owner list with its own history.
        TextButton(onClick = viewModel::duplicateList) { Text("Duplicate") }
        Spacer(Modifier.height(8.dp))

        Button(onClick = viewModel::requestLeave) { Text("Unsubscribe") }
    }

    if (state.isLeaveConfirmOpen) {
        AlertDialog(
            onDismissRequest = viewModel::cancelLeave,
            title = { Text("Leave this list?") },
            text = { Text("You'll stop receiving updates for \"${state.name}\" on this device.") },
            confirmButton = { TextButton(onClick = viewModel::confirmLeave) { Text("Leave") } },
            dismissButton = { TextButton(onClick = viewModel::cancelLeave) { Text("Cancel") } },
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
) {
    val rowHeightPx = with(LocalDensity.current) { 44.dp.toPx() }
    var draggingCategory by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentCategories by rememberUpdatedState(categories)

    Column {
        categories.forEach { category ->
            key(category) {
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
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Reorder $category",
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
