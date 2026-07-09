package org.p23q.shoppinglist.ui.listprops

import android.content.Intent
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ListPropsScreen(
    onLeft: () -> Unit,
    viewModel: ListPropsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadMembers() }
    LaunchedEffect(state.hasLeft) { if (state.hasLeft) onLeft() }
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
        state.categoryOrder.forEachIndexed { index, category ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(category, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.moveCategoryUp(index) }) {
                    Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = "Move $category up")
                }
                IconButton(onClick = { viewModel.moveCategoryDown(index) }) {
                    Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Move $category down")
                }
            }
        }
        TextButton(onClick = { viewModel.saveCategoryOrder() }) { Text("Save order") }
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
