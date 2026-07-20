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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.data.api.AdminUserDto

@Composable
fun AdminScreen(viewModel: AdminViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        Text("Registration", style = MaterialTheme.typography.titleMedium)
        Text(
            "Allow new accounts. Runtime override — resets to the server's configured default on restart.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { viewModel.toggleRegistration() },
            enabled = state.allowRegistration != null,
        ) {
            Text(
                when (state.allowRegistration) {
                    null -> "Loading…"
                    true -> "Registration is ON — turn off"
                    false -> "Registration is OFF — turn on"
                },
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
                onDelete = { viewModel.deleteUser(user) },
            )
            HorizontalDivider()
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
