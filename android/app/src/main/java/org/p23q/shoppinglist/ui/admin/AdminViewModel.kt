package org.p23q.shoppinglist.ui.admin

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
import org.p23q.shoppinglist.core.api.AdminPasswordRequest
import org.p23q.shoppinglist.core.api.AdminUserDto
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.api.ServerSettingsDto
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.UiText
import java.io.IOException
import javax.inject.Inject

data class AdminUiState(
    /** null until the list has been asked for (T-221); the console does not pull it on open. */
    val users: List<AdminUserDto>? = null,
    /** null = not loaded yet. */
    val allowRegistration: Boolean? = null,
    /** The admin's own password, entered once for step-up on reset/delete (T-107). */
    val password: String = "",
    /**
     * Complaint shown AT the password field (T-113). The page-level [error] sits at the top of a
     * scrolling screen, so a blocked reset/delete looked like nothing happened at all.
     */
    val passwordError: UiText? = null,
    val error: UiText? = null,
    /** The most recent reset — its new password is shown once. */
    val resetEmail: String? = null,
    val resetPassword: String? = null,
    val currentAccountId: String? = null,
)

/** Admin-only server console (T-107): registration toggle + reset/delete users, all with step-up. */
@HiltViewModel
class AdminViewModel @Inject constructor(
    private val apiProvider: ApiProvider,
    sessionState: SessionState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUiState(currentAccountId = sessionState.accountId))
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    init {
        // Only the registration flag, which is one value. Pulling every account just to open the
        // console is the wrong default on an instance with hundreds of them (T-221).
        loadSettings()
    }

    fun loadSettings(): Job = viewModelScope.launch {
        try {
            val settings = apiProvider.get().adminGetServerSettings()
            _uiState.update { it.copy(allowRegistration = settings.allowRegistration, error = null) }
        } catch (e: ApiException) {
            _uiState.update { it.copy(error = ErrorText.of(e, R.string.admin_msg_load_failed)) }
        } catch (e: IOException) {
            _uiState.update { it.copy(error = UiText.res(R.string.admin_msg_offline_admin)) }
        }
    }

    /** Fetches (or re-fetches) the user list on request. Server-ordered by email — nothing sorts
     *  it here, so both clients read the same way (T-221). */
    fun loadUsers(): Job = viewModelScope.launch {
        try {
            val users = apiProvider.get().adminUsers().users
            _uiState.update { it.copy(users = users, error = null) }
        } catch (e: ApiException) {
            _uiState.update { it.copy(error = ErrorText.of(e, R.string.admin_msg_load_failed)) }
        } catch (e: IOException) {
            _uiState.update { it.copy(error = UiText.res(R.string.admin_msg_offline_admin)) }
        }
    }

    fun onPasswordChange(value: String) =
        _uiState.update { it.copy(password = value, error = null, passwordError = if (value.isBlank()) it.passwordError else null) }

    /**
     * True (and complains inline) when the step-up password is missing. Callers check this BEFORE
     * doing anything else — notably before opening the delete confirmation, so the user is never
     * asked to confirm a deletion that then can't run (T-113).
     */
    fun requirePassword(): Boolean {
        if (_uiState.value.password.isNotBlank()) {
            _uiState.update { it.copy(passwordError = null) }
            return true
        }
        _uiState.update { it.copy(passwordError = UiText.res(R.string.admin_msg_password_required)) }
        return false
    }

    fun toggleRegistration(): Job? {
        val current = _uiState.value.allowRegistration ?: return null
        return viewModelScope.launch {
            try {
                val result = apiProvider.get().adminSetServerSettings(ServerSettingsDto(!current))
                _uiState.update { it.copy(allowRegistration = result.allowRegistration, error = null) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = ErrorText.of(e, R.string.admin_msg_update_failed)) }
            } catch (e: IOException) {
                _uiState.update { it.copy(error = UiText.res(R.string.admin_msg_offline)) }
            }
        }
    }

    fun resetPassword(user: AdminUserDto): Job? {
        if (!requirePassword()) return null
        val pw = _uiState.value.password
        return viewModelScope.launch {
            try {
                val result = apiProvider.get().adminResetPassword(user.id, AdminPasswordRequest(pw))
                _uiState.update {
                    it.copy(resetEmail = user.email, resetPassword = result.password, error = null)
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = ErrorText.of(e, R.string.admin_msg_reset_failed)) }
            } catch (e: IOException) {
                _uiState.update { it.copy(error = UiText.res(R.string.admin_msg_offline)) }
            }
        }
    }

    fun deleteUser(user: AdminUserDto): Job? {
        if (!requirePassword()) return null
        val pw = _uiState.value.password
        return viewModelScope.launch {
            try {
                apiProvider.get().adminDeleteUser(user.id, AdminPasswordRequest(pw))
                _uiState.update {
                    it.copy(users = it.users?.filterNot { u -> u.id == user.id }, error = null)
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = ErrorText.of(e, R.string.admin_msg_delete_failed)) }
            } catch (e: IOException) {
                _uiState.update { it.copy(error = UiText.res(R.string.admin_msg_offline)) }
            }
        }
    }
}
