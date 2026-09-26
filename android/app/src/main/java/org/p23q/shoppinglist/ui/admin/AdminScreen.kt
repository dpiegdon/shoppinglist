package org.p23q.shoppinglist.ui.admin

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.p23q.shoppinglist.ui.CompactButtonPadding
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.ui.dangerButtonColors
import org.p23q.shoppinglist.core.api.AdminUserDto
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.asString

/** Green track when registration is on, red when it's denied (T-112). */
private val RegistrationOnColor = Color(0xFF2E7D32)

@Composable
fun AdminScreen(viewModel: AdminViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Misclick guard (T-112): deleting a user opens a confirmation naming them, even though the
    // password was already entered.
    var pendingDelete by remember { mutableStateOf<AdminUserDto?>(null) }
    // The same guard for a reset (T-313): it locks the user out of their current password at once.
    var pendingReset by remember { mutableStateOf<AdminUserDto?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        state.error?.let {
            Text(it.asString(), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        Text(stringResource(R.string.admin_registration), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.admin_allow_new_accounts))
                Text(
                    stringResource(R.string.admin_registration_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.allowRegistration == true,
                onCheckedChange = { viewModel.toggleRegistration() },
                enabled = state.allowRegistration != null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = RegistrationOnColor,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.error,
                ),
            )
        }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.admin_users), style = MaterialTheme.typography.titleMedium)
        // One screen, not a submenu (T-221): the console is small, and a second navigation step on
        // both clients would buy nothing. The list is simply not fetched until asked for.
        val users = state.users
        if (users == null) {
            Button(onClick = { viewModel.loadUsers() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.admin_show_users))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.admin_user_count, users.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { viewModel.loadUsers() }, contentPadding = CompactButtonPadding) {
                    Text(stringResource(R.string.action_refresh))
                }
            }
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text(stringResource(R.string.admin_your_password)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = state.passwordError != null,
                supportingText = state.passwordError?.let { { Text(it.asString()) } },
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.resetPassword != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.admin_new_password_for, state.resetEmail ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ResetPasswordRow(state.resetPassword!!)
            }
            Spacer(Modifier.height(8.dp))

            users.forEach { user ->
                UserRow(
                    user = user,
                    deletable = !user.isAdmin && user.id != state.currentAccountId,
                    // The password is checked before the confirmation opens, as for a deletion (T-113).
                    onReset = { if (viewModel.requirePassword()) pendingReset = user },
                    onDelete = { if (viewModel.requirePassword()) pendingDelete = user },
                )
                HorizontalDivider()
            }
        }
    }

    pendingReset?.let { user ->
        LocalizedAlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text(stringResource(R.string.admin_reset_user_title)) },
            text = { Text(stringResource(R.string.admin_reset_user_body, user.email)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetPassword(user)
                    pendingReset = null
                }) {
                    Text(stringResource(R.string.action_reset))
                }
            },
            dismissButton = { TextButton(onClick = { pendingReset = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    pendingDelete?.let { user ->
        LocalizedAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.admin_delete_user_title)) },
            text = { Text(stringResource(R.string.admin_delete_user_body, user.email)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteUser(user)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The new password, selectable in a monospace face, with a Copy button that says so for a moment. */
@Composable
private fun ResetPasswordRow(password: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(password) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(password, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                clipboard.setText(AnnotatedString(password))
                copied = true
            },
            contentPadding = CompactButtonPadding,
        ) {
            Text(stringResource(if (copied) R.string.action_copied else R.string.action_copy))
        }
    }
}

@Composable
private fun UserRow(
    user: AdminUserDto,
    deletable: Boolean,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(user.email + if (user.isAdmin) stringResource(R.string.admin_is_admin_suffix) else "")
            Text(
                stringResource(R.string.admin_session_count, user.sessionCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onReset, contentPadding = CompactButtonPadding) { Text(stringResource(R.string.action_reset)) }
        if (deletable) {
            Spacer(Modifier.width(4.dp))
            Button(onClick = onDelete, colors = dangerButtonColors(), contentPadding = CompactButtonPadding) {
                Text(stringResource(R.string.action_delete))
            }
        }
    }
}
