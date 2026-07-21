package org.p23q.shoppinglist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.p23q.shoppinglist.data.DefaultCurrencyState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.api.ApiException
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.ChangeEmailRequest
import org.p23q.shoppinglist.data.api.ChangePasswordRequest
import org.p23q.shoppinglist.data.api.DeleteAccountRequest
import org.p23q.shoppinglist.data.api.SessionDto
import org.p23q.shoppinglist.data.api.UnauthorizedException
import org.p23q.shoppinglist.data.api.UpdateSettingsRequest
import org.p23q.shoppinglist.data.crash.CrashLogWriter
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import java.io.IOException
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val accountEmail: String? = null,
    /** Configured-admin flag from the login response (T-107); gates the admin entry point. */
    val isAdmin: Boolean = false,
    val defaultCurrency: String = "",
    /**
     * Resolved default-or-override (T-64). `null` means the one-time fetch in [SettingsViewModel]
     * hasn't resolved yet (or failed) — distinct from a genuinely blank/no-override value once
     * loaded, so the field can render empty without that looking like a value the user chose.
     *
     * Note this is the server's *resolved* value: for an account with no override it is the
     * email-derived default, indistinguishable here from a stored one. That's why only
     * [SettingsViewModel.updateInitials] ever sends it, as a deliberate user action — see
     * [SettingsViewModel.updateCurrency] (T-103).
     */
    val initials: String? = null,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val allowSelfSignedCerts: Boolean = false,
    val sessions: List<SessionDto> = emptyList(),
    val currentPassword: String = "",
    val newPassword: String = "",
    val newEmail: String = "",
    val changeEmailPassword: String = "",
    val deleteAccountPassword: String = "",
    val isDeleteConfirmOpen: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val isAccountDeleted: Boolean = false,
    /** Absolute path of the crash log to hand to a share intent (T-50); consumed once fired. */
    val crashLogPath: String? = null,
    /** Global collaborator-change notifications on/off (T-65). */
    val notificationsEnabled: Boolean = true,
    /** Diagnostics: when the background (WorkManager) sync last ran, humanized (T-112). */
    val lastBackgroundSyncText: String = "never",
)

/** Notes: "the usual stuff" — currency, password/email, sessions, delete account, theme, server URL. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiProvider: ApiProvider,
    private val sessionState: SessionState,
    private val serverConfig: ServerConfig,
    private val themePreferenceStore: ThemePreferenceStore,
    private val appDb: AppDb,
    private val crashLogWriter: CrashLogWriter,
    private val defaultCurrencyState: DefaultCurrencyState,
    private val notificationPrefs: NotificationPrefsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Local/cached only - no network call, so sessions (a real request) stay opt-in via
        // loadSessions() instead of firing on every construction (e.g. every settings dialog open).
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    serverUrl = serverConfig.serverUrl.first() ?: "",
                    accountEmail = sessionState.accountEmail,
                    isAdmin = sessionState.isAdmin,
                    defaultCurrency = sessionState.defaultCurrency ?: "",
                    allowSelfSignedCerts = serverConfig.allowSelfSignedCerts.first(),
                )
            }
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
    }

    /** Global collaborator-change notification toggle (T-65); per-list mutes live in list properties. */
    fun setNotificationsEnabled(enabled: Boolean): Job = viewModelScope.launch {
        notificationPrefs.setNotificationsEnabled(enabled)
    }

    fun loadSessions(): Job = viewModelScope.launch {
        try {
            val sessions = apiProvider.get().sessions().sessions
            _uiState.update { it.copy(sessions = sessions) }
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = "Couldn't load sessions") }
        }
    }

    /**
     * Not cached anywhere locally (unlike currency, via sessionState) — a real fetch, opt-in like
     * [loadSessions] rather than in init (this screen's init is local/cached-only by design). The
     * screen calls this once on open. Best-effort: until it resolves the field just renders blank.
     * Nothing else depends on it having resolved — the currency save never sends initials at all
     * (T-103) — so a failed fetch here can't cause a wrong write, only an empty field.
     */
    fun loadInitials(): Job = viewModelScope.launch {
        try {
            _uiState.update { it.copy(initials = apiProvider.get().getSettings().initials) }
        } catch (e: IOException) {
            // Offline — the initials section just starts blank; not a hard error for this screen.
        }
    }

    fun onCurrentPasswordChange(value: String) = _uiState.update { it.copy(currentPassword = value, errorMessage = null) }

    fun onNewPasswordChange(value: String) = _uiState.update { it.copy(newPassword = value, errorMessage = null) }

    fun onNewEmailChange(value: String) = _uiState.update { it.copy(newEmail = value, errorMessage = null) }

    fun onChangeEmailPasswordChange(value: String) = _uiState.update { it.copy(changeEmailPassword = value, errorMessage = null) }

    fun onDeleteAccountPasswordChange(value: String) = _uiState.update { it.copy(deleteAccountPassword = value, errorMessage = null) }

    fun updateCurrency(currency: String): Job? {
        val normalized = currency.trim().uppercase()
        if (!ISO_CURRENCY.matches(normalized)) {
            _uiState.update { it.copy(errorMessage = "Enter a valid 3-letter currency code") }
            return null
        }
        return viewModelScope.launch {
            try {
                // Never send initials from the currency save. The server treats an absent key as
                // "leave unchanged" (T-87), not PUT-style overwrite, so passing null here omits it
                // from the request entirely (kotlinx elides a property equal to its declared
                // default) and the stored value is untouched.
                //
                // Resending it was actively harmful (T-103): GET /settings returns the *resolved*
                // value, so an account with no override reads back the email-derived default
                // ("BO" for bob@…) with nothing marking it as derived. Echoing that back stored it
                // as an explicit override, pinning the initials so a later email change no longer
                // re-derived them. T-97 fixed only the narrower unresolved-preload case.
                val response = apiProvider.get().updateSettings(
                    UpdateSettingsRequest(normalized, initials = null),
                )
                sessionState.defaultCurrency = response.defaultCurrency
                // Also updates the in-memory mirror (T-55) so an already-open list screen picks up
                // the change immediately instead of only the next time it's opened.
                defaultCurrencyState.set(response.defaultCurrency)
                _uiState.update {
                    it.copy(
                        defaultCurrency = response.defaultCurrency,
                        initials = response.initials,
                        errorMessage = null,
                        infoMessage = "Currency updated",
                    )
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Couldn't update currency") }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = "Couldn't reach the server") }
            }
        }
    }

    fun onInitialsChange(value: String) = _uiState.update { it.copy(initials = value, errorMessage = null) }

    fun updateInitials(initials: String): Job? {
        val normalized = initials.trim().uppercase()
        if (normalized.length > INITIALS_MAX_LENGTH) {
            _uiState.update { it.copy(errorMessage = "Initials must be $INITIALS_MAX_LENGTH characters or fewer") }
            return null
        }
        return viewModelScope.launch {
            try {
                // No longer required by the server (T-87: absent key = unchanged); kept for
                // parity. Currency is always loaded from cached session state, so unlike
                // initials there's no staleness risk in resending it here.
                val response = apiProvider.get().updateSettings(
                    UpdateSettingsRequest(_uiState.value.defaultCurrency, normalized),
                )
                _uiState.update {
                    it.copy(initials = response.initials, errorMessage = null, infoMessage = "Initials updated")
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Couldn't update initials") }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = "Couldn't reach the server") }
            }
        }
    }

    fun changePassword(): Job? {
        val state = _uiState.value
        if (state.currentPassword.isBlank() || state.newPassword.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Both password fields are required") }
            return null
        }
        return viewModelScope.launch {
            try {
                apiProvider.get().changePassword(ChangePasswordRequest(state.currentPassword, state.newPassword))
                _uiState.update {
                    it.copy(currentPassword = "", newPassword = "", errorMessage = null, infoMessage = "Password changed")
                }
            } catch (e: UnauthorizedException) {
                _uiState.update { it.copy(errorMessage = "Current password is incorrect") }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Couldn't change password") }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = "Couldn't reach the server") }
            }
        }
    }

    fun changeEmail(): Job? {
        val state = _uiState.value
        if (state.changeEmailPassword.isBlank() || state.newEmail.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Password and new email are required") }
            return null
        }
        return viewModelScope.launch {
            try {
                apiProvider.get().changeEmail(ChangeEmailRequest(state.changeEmailPassword, state.newEmail))
                sessionState.accountEmail = state.newEmail
                _uiState.update {
                    it.copy(
                        accountEmail = state.newEmail,
                        newEmail = "",
                        changeEmailPassword = "",
                        errorMessage = null,
                        infoMessage = "Email changed",
                    )
                }
            } catch (e: UnauthorizedException) {
                _uiState.update { it.copy(errorMessage = "Password is incorrect") }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Couldn't change email") }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = "Couldn't reach the server") }
            }
        }
    }

    fun revokeSession(id: String): Job = viewModelScope.launch {
        try {
            apiProvider.get().revokeSession(id)
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = "Couldn't revoke that session") }
        }
        loadSessions().join()
    }

    fun requestDeleteAccount() = _uiState.update { it.copy(isDeleteConfirmOpen = true) }

    fun cancelDeleteAccount() = _uiState.update { it.copy(isDeleteConfirmOpen = false, deleteAccountPassword = "") }

    fun confirmDeleteAccount(): Job? {
        val password = _uiState.value.deleteAccountPassword
        if (password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Password is required") }
            return null
        }
        return viewModelScope.launch {
            try {
                apiProvider.get().deleteAccount(DeleteAccountRequest(password))
                sessionState.clear()
                withContext(Dispatchers.IO) { appDb.clearAllTables() }
                _uiState.update { it.copy(isAccountDeleted = true, isDeleteConfirmOpen = false) }
            } catch (e: UnauthorizedException) {
                _uiState.update { it.copy(errorMessage = "Password is incorrect") }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Couldn't delete account") }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = "Couldn't reach the server") }
            }
        }
    }

    /** No log yet, or an empty one, surfaces a message instead of firing an empty share sheet (T-50). */
    fun shareLogs() {
        val file = crashLogWriter.logFile
        if (file.exists() && file.length() > 0) {
            _uiState.update { it.copy(crashLogPath = file.absolutePath) }
        } else {
            _uiState.update { it.copy(infoMessage = "No crash logs yet") }
        }
    }

    fun consumeCrashLogShare() = _uiState.update { it.copy(crashLogPath = null) }

    fun setTheme(preference: ThemePreference): Job = viewModelScope.launch { themePreferenceStore.setTheme(preference) }

    /**
     * Dev-only (the Settings toggle that calls this is gated to debug builds). Persists the flag;
     * it only actually affects TLS in debug builds — release ignores it (see DevCertTrust.kt).
     */
    fun setAllowSelfSignedCerts(allow: Boolean): Job = viewModelScope.launch {
        serverConfig.setAllowSelfSignedCerts(allow)
        _uiState.update { it.copy(allowSelfSignedCerts = allow) }
    }

    private companion object {
        val ISO_CURRENCY = Regex("^[A-Z]{3}$")
        const val INITIALS_MAX_LENGTH = 3
    }
}

/** Coarse "how long ago" for the background-sync diagnostic (T-112); 0 = never ran. */
internal fun formatBackgroundSync(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0L) return "never"
    val elapsed = now - at
    return when {
        elapsed < 60_000 -> "just now"
        elapsed < 3_600_000 -> "${elapsed / 60_000} min ago"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000} h ago"
        else -> "${elapsed / 86_400_000} d ago"
    }
}
