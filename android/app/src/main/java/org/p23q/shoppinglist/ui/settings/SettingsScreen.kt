package org.p23q.shoppinglist.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.data.ThemePreference
import java.io.File
import org.p23q.shoppinglist.ui.asString
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.LanguagePicker
import org.p23q.shoppinglist.core.AppLocale
import org.p23q.shoppinglist.data.deviceLocale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    // Plain hoisted state, not a second hiltViewModel() default — see LoginScreen (T-127).
    selectedLocale: AppLocale = deviceLocale(),
    onSelectLocale: (AppLocale) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
        LanguagePicker(selected = selectedLocale, onSelect = onSelectLocale)
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
        // Only server accounts have collaborators, so a phone with none has nothing to notify of.
        if (state.hasServerAccount) {
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
        }

        // No telemetry service (T-50) — this is purely local, opt-in, and manual: the crash log
        // never leaves the device unless the user explicitly shares it here.
        Text(stringResource(R.string.settings_diagnostics), style = MaterialTheme.typography.titleMedium)
        // On-device way to check that background sync (WorkManager) actually runs (T-112) — if this
        // stays "never" while the app is closed, the OS is likely killing background work (battery
        // optimization / Doze), which is also why collaborator-change notifications wouldn't fire.
        // The local area never syncs, so without a server account there is no background sync.
        if (state.hasServerAccount) {
            Text(
                stringResource(R.string.settings_last_background_sync, state.lastBackgroundSyncText.asString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = viewModel::shareLogs) { Text(stringResource(R.string.settings_share_crash_logs)) }
        state.infoMessage?.let { Text(it.asString(), color = MaterialTheme.colorScheme.primary) }
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
