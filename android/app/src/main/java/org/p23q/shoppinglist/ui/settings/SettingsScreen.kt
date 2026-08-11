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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.BuildConfig
import org.p23q.shoppinglist.data.ThemePreference
import java.io.File
import org.p23q.shoppinglist.ui.asString
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.LanguagePicker
import org.p23q.shoppinglist.data.AppLocale
import org.p23q.shoppinglist.data.deviceLocale

@Composable
fun SettingsScreen(
    onAccountDeleted: () -> Unit,
    onOpenAdmin: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    // Plain hoisted state, not a second hiltViewModel() default — see LoginScreen (T-127).
    selectedLocale: AppLocale = deviceLocale(),
    onSelectLocale: (AppLocale) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    LaunchedEffect(Unit) { viewModel.loadSessions() }
    LaunchedEffect(Unit) { viewModel.loadInitials() }
    LaunchedEffect(state.isAccountDeleted) { if (state.isAccountDeleted) onAccountDeleted() }
    // Hoisted above the effect: its body is a coroutine, not a composition.
    val shareCrashLogsTitle = stringResource(R.string.settings_share_crash_logs)
    LaunchedEffect(state.crashLogPath) {
        val path = state.crashLogPath
        if (path != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, shareCrashLogsTitle))
            viewModel.consumeCrashLogShare()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Admin-only entry to the server console (T-107); shown from the login response flag.
        if (state.isAdmin) {
            Button(onClick = onOpenAdmin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.nav_server_admin)) }
            Spacer(Modifier.height(16.dp))
        }

        LanguagePicker(selected = selectedLocale, onSelect = onSelectLocale)
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium)
        state.accountEmail?.let { Text(it) }
        Text(stringResource(R.string.settings_server, state.serverUrl), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.settings_default_currency), style = MaterialTheme.typography.titleMedium)
        var currencyInput by remember(state.defaultCurrency) { mutableStateOf(state.defaultCurrency) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = currencyInput,
                onValueChange = { currencyInput = it },
                label = { Text(stringResource(R.string.settings_currency)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.updateCurrency(currencyInput) }) { Text(stringResource(R.string.action_save)) }
        }
        Spacer(Modifier.height(16.dp))

        // Shown as a small badge on shared-list item rows so collaborators can see who last
        // touched an item (T-64); defaults to the email's initials until customized here.
        Text(stringResource(R.string.settings_display_initials), style = MaterialTheme.typography.titleMedium)
        // state.initials is null until the preload resolves (T-97); the field just starts blank.
        var initialsInput by remember(state.initials) { mutableStateOf(state.initials ?: "") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = initialsInput,
                onValueChange = { initialsInput = it },
                label = { Text(stringResource(R.string.settings_initials)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.updateInitials(initialsInput) }) { Text(stringResource(R.string.action_save)) }
        }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
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
        Text(stringResource(R.string.settings_notifications), style = MaterialTheme.typography.titleMedium)
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_collaborator_changes))
                Text(
                    stringResource(R.string.settings_collaborator_changes_help),
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

        // App updates (T-135). Device-local like the notification toggle above — whether this
        // phone checks is a property of the phone, not the account, so it isn't synced. Off
        // means no request at all, not a silent check.
        Text(stringResource(R.string.settings_updates), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_auto_update_check))
                Text(
                    stringResource(R.string.settings_auto_update_check_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.autoUpdateCheckEnabled,
                onCheckedChange = { viewModel.setAutoUpdateCheckEnabled(it) },
            )
        }
        Spacer(Modifier.height(16.dp))

        // Developer-only escape hatch for testing against a self-signed dev server. Present only in
        // debug builds; even if this flag were somehow set, release builds ignore it (DevCertTrust).
        if (BuildConfig.DEBUG) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_trust_self_signed), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_trust_self_signed_help),
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

        Text(stringResource(R.string.settings_change_password), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.currentPassword,
            onValueChange = viewModel::onCurrentPasswordChange,
            label = { Text(stringResource(R.string.settings_current_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.newPassword,
            onValueChange = viewModel::onNewPasswordChange,
            label = { Text(stringResource(R.string.settings_new_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = viewModel::changePassword) { Text(stringResource(R.string.settings_change_password)) }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.settings_change_email), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.newEmail,
            onValueChange = viewModel::onNewEmailChange,
            label = { Text(stringResource(R.string.settings_new_email)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.changeEmailPassword,
            onValueChange = viewModel::onChangeEmailPasswordChange,
            label = { Text(stringResource(R.string.settings_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = viewModel::changeEmail) { Text(stringResource(R.string.settings_change_email)) }
        Spacer(Modifier.height(16.dp))

        state.errorMessage?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        state.infoMessage?.let { Text(it.asString(), color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.settings_sessions), style = MaterialTheme.typography.titleMedium)
        state.sessions.forEach { session ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text((session.deviceLabel ?: stringResource(R.string.settings_unknown_device)) + if (session.current) " (this device)" else "")
                    // The current session is active by definition — this request is it. Showing
                    // its stored lastSeenAt instead would read as up to 15 minutes stale, since
                    // the server throttles that write (auth.LAST_SEEN_REFRESH_MS).
                    Text(
                        if (session.current) {
                            stringResource(R.string.last_seen_active_now)
                        } else {
                            formatLastSeen(session.lastSeenAt).asString()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!session.current) {
                    TextButton(onClick = { viewModel.revokeSession(session.id) }) { Text(stringResource(R.string.action_revoke)) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.settings_danger_zone), style = MaterialTheme.typography.titleMedium)
        // Filled red button (T-112), matching the Clear-checked danger action — not an easy-to-miss
        // text button. Confirmation still gates the actual delete.
        Button(
            onClick = viewModel::requestDeleteAccount,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_delete_account)) }
        Spacer(Modifier.height(16.dp))

        // No telemetry service (T-50) — this is purely local, opt-in, and manual: the crash log
        // never leaves the device unless the user explicitly shares it here.
        Text(stringResource(R.string.settings_diagnostics), style = MaterialTheme.typography.titleMedium)
        // On-device way to check that background sync (WorkManager) actually runs (T-112) — if this
        // stays "never" while the app is closed, the OS is likely killing background work (battery
        // optimization / Doze), which is also why collaborator-change notifications wouldn't fire.
        Text(
            stringResource(R.string.settings_last_background_sync, state.lastBackgroundSyncText.asString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = viewModel::shareLogs) { Text(stringResource(R.string.settings_share_crash_logs)) }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.settings_version, appVersion), style = MaterialTheme.typography.bodySmall)
    }

    if (state.isDeleteConfirmOpen) {
        LocalizedAlertDialog(
            onDismissRequest = viewModel::cancelDeleteAccount,
            title = { Text(stringResource(R.string.settings_delete_account_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_delete_account_body))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.deleteAccountPassword,
                        onValueChange = viewModel::onDeleteAccountPasswordChange,
                        label = { Text(stringResource(R.string.settings_confirm_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmDeleteAccount) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelDeleteAccount) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * User-facing theme names (T-40) — never show the raw SYSTEM/LIGHT/DARK enum constants.
 *
 * @Composable so the labels resolve through LocalizedContent's locale rather than the system one.
 */
@Composable
private fun ThemePreference.label(): String = when (this) {
    ThemePreference.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemePreference.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemePreference.DARK -> stringResource(R.string.settings_theme_dark)
}
