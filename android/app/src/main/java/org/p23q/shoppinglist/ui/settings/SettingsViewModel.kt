package org.p23q.shoppinglist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.sync.ChangeCheckOutcome
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.crash.CrashLogWriter
import org.p23q.shoppinglist.data.notify.ChangeCheck
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.UiText
import javax.inject.Inject

data class SettingsUiState(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val infoMessage: UiText? = null,
    /** Absolute path of the crash log to hand to a share intent (T-50); consumed once fired. */
    val crashLogPath: String? = null,
    /** Global collaborator-change notifications on/off (T-65). */
    val notificationsEnabled: Boolean = true,
    /** Diagnostics: when the background (WorkManager) sync last ran, humanized (T-112). */
    val lastBackgroundSyncText: UiText = UiText.res(R.string.background_sync_never),
    /** Diagnostics: the last collaborator-change check and what decided it (T-318). */
    val lastChangeCheckText: UiText = UiText.res(R.string.settings_last_change_check_never),
    /**
     * Whether the phone holds a server account. Without one nothing syncs and no collaborator
     * changes anything, so the notification switch and the background-sync line are hidden (T-302).
     */
    val hasServerAccount: Boolean = true,
)

/**
 * What is this phone's rather than an account's: theme, language, notifications and diagnostics.
 * Everything an account owns lives on its Account screen (T-292).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferenceStore: ThemePreferenceStore,
    private val crashLogWriter: CrashLogWriter,
    private val notificationPrefs: NotificationPrefsStore,
    private val registry: AccountRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(hasServerAccount = registry.snapshot().any { it.isServer }))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            registry.accounts.collect { accounts -> _uiState.update { it.copy(hasServerAccount = accounts.any { a -> a.isServer }) } }
        }
        viewModelScope.launch {
            themePreferenceStore.theme.collect { pref -> _uiState.update { it.copy(theme = pref) } }
        }
        viewModelScope.launch {
            notificationPrefs.notificationsEnabled.collect { enabled ->
                _uiState.update { it.copy(notificationsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            notificationPrefs.lastBackgroundSyncAt.collect { at ->
                _uiState.update { it.copy(lastBackgroundSyncText = formatBackgroundSync(at)) }
            }
        }
        viewModelScope.launch {
            notificationPrefs.lastChangeCheck.collect { check ->
                _uiState.update { it.copy(lastChangeCheckText = formatChangeCheck(check)) }
            }
        }
    }

    /** Global collaborator-change notification toggle (T-65); per-list mutes live in list properties. */
    fun setNotificationsEnabled(enabled: Boolean): Job = viewModelScope.launch {
        notificationPrefs.setNotificationsEnabled(enabled)
    }

    /** No log yet, or an empty one, surfaces a message instead of firing an empty share sheet (T-50). */
    fun shareLogs() {
        val file = crashLogWriter.logFile
        if (file.exists() && file.length() > 0) {
            _uiState.update { it.copy(crashLogPath = file.absolutePath) }
        } else {
            _uiState.update { it.copy(infoMessage = UiText.res(R.string.settings_msg_no_crash_logs)) }
        }
    }

    fun consumeCrashLogShare() = _uiState.update { it.copy(crashLogPath = null) }

    fun setTheme(preference: ThemePreference): Job = viewModelScope.launch { themePreferenceStore.setTheme(preference) }
}

/** Coarse "how long ago" for the background-sync diagnostic (T-112); 0 = never ran. */
internal fun formatBackgroundSync(at: Long, now: Long = System.currentTimeMillis()): UiText {
    if (at <= 0L) return UiText.res(R.string.background_sync_never)
    val elapsed = now - at
    return when {
        elapsed < 60_000 -> UiText.res(R.string.ago_just_now)
        elapsed < 3_600_000 -> UiText.res(R.string.ago_minutes, (elapsed / 60_000).toInt())
        elapsed < 86_400_000 -> UiText.res(R.string.ago_hours, (elapsed / 3_600_000).toInt())
        else -> UiText.res(R.string.ago_days, (elapsed / 86_400_000).toInt())
    }
}

/** "Last change check: 5 min ago, foreign items pulled: 2, list muted" (T-318); null = none yet. */
internal fun formatChangeCheck(check: ChangeCheck?, now: Long = System.currentTimeMillis()): UiText {
    if (check == null) return UiText.res(R.string.settings_last_change_check_never)
    return UiText.res(
        R.string.settings_last_change_check,
        formatBackgroundSync(check.atMillis, now),
        check.foreignItems,
        UiText.res(check.outcome.label()),
    )
}

private fun ChangeCheckOutcome.label(): Int = when (this) {
    ChangeCheckOutcome.FIRST_SYNC -> R.string.change_check_first_sync
    ChangeCheckOutcome.NOTHING_FOREIGN -> R.string.change_check_nothing_foreign
    ChangeCheckOutcome.FOREGROUND -> R.string.change_check_foreground
    ChangeCheckOutcome.NOTIFICATIONS_OFF -> R.string.change_check_notifications_off
    ChangeCheckOutcome.LIST_MUTED -> R.string.change_check_list_muted
    ChangeCheckOutcome.NO_PERMISSION -> R.string.change_check_no_permission
    ChangeCheckOutcome.POSTED -> R.string.change_check_posted
}
