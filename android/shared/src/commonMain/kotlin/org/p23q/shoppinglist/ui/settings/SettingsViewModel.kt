package org.p23q.shoppinglist.ui.settings

import org.p23q.shoppinglist.core.Clock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.sync.ChangeCheckOutcome
import org.p23q.shoppinglist.core.sync.InviteCheckOutcome
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.crash.CrashLog
import org.p23q.shoppinglist.data.notify.ChangeCheck
import org.p23q.shoppinglist.data.notify.InviteCheck
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.ago_days
import org.p23q.shoppinglist.resources.ago_hours
import org.p23q.shoppinglist.resources.ago_just_now
import org.p23q.shoppinglist.resources.ago_minutes
import org.p23q.shoppinglist.resources.background_sync_never
import org.p23q.shoppinglist.resources.change_check_first_sync
import org.p23q.shoppinglist.resources.change_check_foreground
import org.p23q.shoppinglist.resources.change_check_list_muted
import org.p23q.shoppinglist.resources.change_check_no_permission
import org.p23q.shoppinglist.resources.change_check_nothing_foreign
import org.p23q.shoppinglist.resources.change_check_notifications_off
import org.p23q.shoppinglist.resources.change_check_posted
import org.p23q.shoppinglist.resources.invite_check_invites_off
import org.p23q.shoppinglist.resources.invite_check_nothing_new
import org.p23q.shoppinglist.resources.settings_last_change_check
import org.p23q.shoppinglist.resources.settings_last_change_check_never
import org.p23q.shoppinglist.resources.settings_last_invite_check
import org.p23q.shoppinglist.resources.settings_last_invite_check_never
import org.p23q.shoppinglist.resources.settings_msg_no_crash_logs
import org.p23q.shoppinglist.ui.UiText

data class SettingsUiState(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val infoMessage: UiText? = null,
    /** Absolute path of the crash log to hand to a share intent (T-50); consumed once fired. */
    val crashLogPath: String? = null,
    /** Global collaborator-change notifications on/off (T-65). */
    val notificationsEnabled: Boolean = true,
    /** Invite notifications on/off (T-319), apart from the collaborator switch. */
    val inviteNotificationsEnabled: Boolean = true,
    /** Diagnostics: when the background (WorkManager) sync last ran, humanized (T-112). */
    val lastBackgroundSyncText: UiText = UiText.res(Res.string.background_sync_never),
    /** Diagnostics: the last collaborator-change check and what decided it (T-318). */
    val lastChangeCheckText: UiText = UiText.res(Res.string.settings_last_change_check_never),
    /** Diagnostics: the last invite check and what decided it (T-319). */
    val lastInviteCheckText: UiText = UiText.res(Res.string.settings_last_invite_check_never),
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
class SettingsViewModel(
    private val themePreferenceStore: ThemePreferenceStore,
    private val crashLogWriter: CrashLog,
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
            notificationPrefs.inviteNotificationsEnabled.collect { enabled ->
                _uiState.update { it.copy(inviteNotificationsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            notificationPrefs.lastInviteCheck.collect { check ->
                _uiState.update { it.copy(lastInviteCheckText = formatInviteCheck(check)) }
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

    /** Invite notifications on/off (T-319). */
    fun setInviteNotificationsEnabled(enabled: Boolean): Job = viewModelScope.launch {
        notificationPrefs.setInviteNotificationsEnabled(enabled)
    }

    /** No log yet, or an empty one, surfaces a message instead of firing an empty share sheet (T-50). */
    fun shareLogs() {
        val path = crashLogWriter.shareablePath()
        if (path != null) {
            _uiState.update { it.copy(crashLogPath = path) }
        } else {
            _uiState.update { it.copy(infoMessage = UiText.res(Res.string.settings_msg_no_crash_logs)) }
        }
    }

    fun consumeCrashLogShare() = _uiState.update { it.copy(crashLogPath = null) }

    fun setTheme(preference: ThemePreference): Job = viewModelScope.launch { themePreferenceStore.setTheme(preference) }
}

/** Coarse "how long ago" for the background-sync diagnostic (T-112); 0 = never ran. */
internal fun formatBackgroundSync(at: Long, now: Long = Clock.System.nowMillis()): UiText {
    if (at <= 0L) return UiText.res(Res.string.background_sync_never)
    val elapsed = now - at
    return when {
        elapsed < 60_000 -> UiText.res(Res.string.ago_just_now)
        elapsed < 3_600_000 -> UiText.res(Res.string.ago_minutes, (elapsed / 60_000).toInt())
        elapsed < 86_400_000 -> UiText.res(Res.string.ago_hours, (elapsed / 3_600_000).toInt())
        else -> UiText.res(Res.string.ago_days, (elapsed / 86_400_000).toInt())
    }
}

/** "Last change check: 5 min ago, foreign items pulled: 2, list muted" (T-318); null = none yet. */
internal fun formatChangeCheck(check: ChangeCheck?, now: Long = Clock.System.nowMillis()): UiText {
    if (check == null) return UiText.res(Res.string.settings_last_change_check_never)
    return UiText.res(
        Res.string.settings_last_change_check,
        formatBackgroundSync(check.atMillis, now),
        check.foreignItems,
        UiText.res(check.outcome.label()),
    )
}

/** "Last invite check: 5 min ago, new invitations: 1, posted" (T-319); null = none yet. */
internal fun formatInviteCheck(check: InviteCheck?, now: Long = Clock.System.nowMillis()): UiText {
    if (check == null) return UiText.res(Res.string.settings_last_invite_check_never)
    return UiText.res(
        Res.string.settings_last_invite_check,
        formatBackgroundSync(check.atMillis, now),
        check.newInvites,
        UiText.res(check.outcome.label()),
    )
}

/** The change check's phrases where they mean the same (T-319). */
private fun InviteCheckOutcome.label(): StringResource = when (this) {
    InviteCheckOutcome.NOTHING_NEW -> Res.string.invite_check_nothing_new
    InviteCheckOutcome.FOREGROUND -> Res.string.change_check_foreground
    InviteCheckOutcome.INVITES_OFF -> Res.string.invite_check_invites_off
    InviteCheckOutcome.NO_PERMISSION -> Res.string.change_check_no_permission
    InviteCheckOutcome.POSTED -> Res.string.change_check_posted
}

private fun ChangeCheckOutcome.label(): StringResource = when (this) {
    ChangeCheckOutcome.FIRST_SYNC -> Res.string.change_check_first_sync
    ChangeCheckOutcome.NOTHING_FOREIGN -> Res.string.change_check_nothing_foreign
    ChangeCheckOutcome.FOREGROUND -> Res.string.change_check_foreground
    ChangeCheckOutcome.NOTIFICATIONS_OFF -> Res.string.change_check_notifications_off
    ChangeCheckOutcome.LIST_MUTED -> Res.string.change_check_list_muted
    ChangeCheckOutcome.NO_PERMISSION -> Res.string.change_check_no_permission
    ChangeCheckOutcome.POSTED -> Res.string.change_check_posted
}
