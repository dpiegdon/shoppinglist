package org.p23q.shoppinglist.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.BuildConfig
import org.p23q.shoppinglist.data.ThemePreference
import java.io.File

@Composable
fun SettingsScreen(
    onAccountDeleted: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    LaunchedEffect(Unit) { viewModel.loadSessions() }
    LaunchedEffect(Unit) { viewModel.loadInitials() }
    LaunchedEffect(state.isAccountDeleted) { if (state.isAccountDeleted) onAccountDeleted() }
    LaunchedEffect(state.crashLogPath) {
        val path = state.crashLogPath
        if (path != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share crash logs"))
            viewModel.consumeCrashLogShare()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        state.accountEmail?.let { Text(it) }
        Text("Server: ${state.serverUrl}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))

        Text("Default currency", style = MaterialTheme.typography.titleMedium)
        var currencyInput by remember(state.defaultCurrency) { mutableStateOf(state.defaultCurrency) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = currencyInput,
                onValueChange = { currencyInput = it },
                label = { Text("Currency") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.updateCurrency(currencyInput) }) { Text("Save") }
        }
        Spacer(Modifier.height(16.dp))

        // Shown as a small badge on shared-list item rows so collaborators can see who last
        // touched an item (T-64); defaults to the email's initials until customized here.
        Text("Display initials", style = MaterialTheme.typography.titleMedium)
        var initialsInput by remember(state.initials) { mutableStateOf(state.initials) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = initialsInput,
                onValueChange = { initialsInput = it },
                label = { Text("Initials") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.updateInitials(initialsInput) }) { Text("Save") }
        }
        Spacer(Modifier.height(16.dp))

        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Row {
            ThemePreference.entries.forEach { pref ->
                FilterChip(
                    selected = state.theme == pref,
                    onClick = { viewModel.setTheme(pref) },
                    label = { Text(pref.label()) },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Collaborator-change notifications (T-65); mute individual lists in their list properties.
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Collaborator changes")
                Text(
                    "Notify when someone else edits a shared list. Mute individual lists in their list properties.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.notificationsEnabled,
                onCheckedChange = { enabled ->
                    viewModel.setNotificationsEnabled(enabled)
                    // API 33+ needs the runtime permission; requested on enable (not cold start) per T-65.
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }
        Spacer(Modifier.height(16.dp))

        // Developer-only escape hatch for testing against a self-signed dev server. Present only in
        // debug builds; even if this flag were somehow set, release builds ignore it (DevCertTrust).
        if (BuildConfig.DEBUG) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Trust self-signed certificates", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Developer option — skips TLS certificate checks. Insecure; debug builds only.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.allowSelfSignedCerts,
                    onCheckedChange = { viewModel.setAllowSelfSignedCerts(it) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("Change password", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.currentPassword,
            onValueChange = viewModel::onCurrentPasswordChange,
            label = { Text("Current password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.newPassword,
            onValueChange = viewModel::onNewPasswordChange,
            label = { Text("New password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = viewModel::changePassword) { Text("Change password") }
        Spacer(Modifier.height(16.dp))

        Text("Change email", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.newEmail,
            onValueChange = viewModel::onNewEmailChange,
            label = { Text("New email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.changeEmailPassword,
            onValueChange = viewModel::onChangeEmailPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = viewModel::changeEmail) { Text("Change email") }
        Spacer(Modifier.height(16.dp))

        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.infoMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))

        Text("Sessions", style = MaterialTheme.typography.titleMedium)
        state.sessions.forEach { session ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((session.deviceLabel ?: "Unknown device") + if (session.current) " (this device)" else "")
                if (!session.current) {
                    TextButton(onClick = { viewModel.revokeSession(session.id) }) { Text("Revoke") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Text("Danger zone", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = viewModel::requestDeleteAccount) {
            Text("Delete account", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))

        // No telemetry service (T-50) — this is purely local, opt-in, and manual: the crash log
        // never leaves the device unless the user explicitly shares it here.
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = viewModel::shareLogs) { Text("Share crash logs") }
        Spacer(Modifier.height(16.dp))

        Text("Version $appVersion", style = MaterialTheme.typography.bodySmall)
    }

    if (state.isDeleteConfirmOpen) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDeleteAccount,
            title = { Text("Delete account?") },
            text = {
                Column {
                    Text("This permanently deletes your account and all data. Enter your password to confirm.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.deleteAccountPassword,
                        onValueChange = viewModel::onDeleteAccountPasswordChange,
                        label = { Text("Confirm password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmDeleteAccount) { Text("Delete") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDeleteAccount) { Text("Cancel") } },
        )
    }
}

/** User-facing theme names (T-40) — never show the raw SYSTEM/LIGHT/DARK enum constants. */
private fun ThemePreference.label(): String = when (this) {
    ThemePreference.SYSTEM -> "System"
    ThemePreference.LIGHT -> "Light"
    ThemePreference.DARK -> "Dark"
}
