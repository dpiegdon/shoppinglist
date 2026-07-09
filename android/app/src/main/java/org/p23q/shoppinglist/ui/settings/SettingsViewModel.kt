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
import org.p23q.shoppinglist.data.db.AppDb
import java.io.IOException
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val accountEmail: String? = null,
    val defaultCurrency: String = "",
    val theme: ThemePreference = ThemePreference.SYSTEM,
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
)

/** Notes: "the usual stuff" — currency, password/email, sessions, delete account, theme, server URL. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiProvider: ApiProvider,
    private val sessionState: SessionState,
    private val serverConfig: ServerConfig,
    private val themePreferenceStore: ThemePreferenceStore,
    private val appDb: AppDb,
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
                    defaultCurrency = sessionState.defaultCurrency ?: "",
                )
            }
        }
        viewModelScope.launch {
            themePreferenceStore.theme.collect { pref -> _uiState.update { it.copy(theme = pref) } }
        }
    }

    fun loadSessions(): Job = viewModelScope.launch {
        try {
            val sessions = apiProvider.get().sessions().sessions
            _uiState.update { it.copy(sessions = sessions) }
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = "Couldn't load sessions") }
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
                val response = apiProvider.get().updateSettings(UpdateSettingsRequest(normalized))
                sessionState.defaultCurrency = response.defaultCurrency
                _uiState.update {
                    it.copy(defaultCurrency = response.defaultCurrency, errorMessage = null, infoMessage = "Currency updated")
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Couldn't update currency") }
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

    fun setTheme(preference: ThemePreference): Job = viewModelScope.launch { themePreferenceStore.setTheme(preference) }

    private companion object {
        val ISO_CURRENCY = Regex("^[A-Z]{3}$")
    }
}
