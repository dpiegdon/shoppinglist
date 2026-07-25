package org.p23q.shoppinglist.ui.admin

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.data.api.AdminUserDto

/** Green track when registration is on, red when it's denied (T-112). */
private val RegistrationOnColor = Color(0xFF2E7D32)

@Composable
fun AdminScreen(viewModel: AdminViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Misclick guard (T-112): deleting a user opens a confirmation naming them, even though the
    // password was already entered.
    var pendingDelete by remember { mutableStateOf<AdminUserDto?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        Text("Registration", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Allow new accounts")
                Text(
                    "Runtime override — resets to the server's configured default on restart.",
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

        Text("Users", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Your password (for reset/delete)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            isError = state.passwordError != null,
            supportingText = state.passwordError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.resetPassword != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "New password for ${state.resetEmail} — shown once, send it securely:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(state.resetPassword!!, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(8.dp))

        state.users.forEach { user ->
            UserRow(
                user = user,
                deletable = !user.isAdmin && user.id != state.currentAccountId,
                onReset = { viewModel.resetPassword(user) },
                onDelete = { if (viewModel.requirePassword()) pendingDelete = user },
            )
            HorizontalDivider()
        }
    }

    pendingDelete?.let { user ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete user?") },
            text = { Text("Permanently delete ${user.email} and all of their data. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteUser(user)
                    pendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
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
            Text(user.email + if (user.isAdmin) " (admin)" else "")
            Text(
                "${user.sessionCount} session" + if (user.sessionCount == 1) "" else "s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onReset) { Text("Reset") }
        if (deletable) {
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
