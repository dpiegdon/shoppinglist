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
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.api.AdminPasswordRequest
import org.p23q.shoppinglist.data.api.AdminUserDto
import org.p23q.shoppinglist.data.api.ApiException
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.ServerSettingsDto
import java.io.IOException
import javax.inject.Inject

data class AdminUiState(
    val users: List<AdminUserDto> = emptyList(),
    /** null = not loaded yet. */
    val allowRegistration: Boolean? = null,
    /** The admin's own password, entered once for step-up on reset/delete (T-107). */
    val password: String = "",
    val error: String? = null,
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
        load()
    }

    fun load(): Job = viewModelScope.launch {
        try {
            val users = apiProvider.get().adminUsers().users
            val settings = apiProvider.get().adminGetServerSettings()
            _uiState.update {
                it.copy(users = users, allowRegistration = settings.allowRegistration, error = null)
            }
        } catch (e: ApiException) {
            _uiState.update { it.copy(error = e.message ?: "Couldn't load admin data") }
        } catch (e: IOException) {
            _uiState.update { it.copy(error = "You're offline; admin needs a connection") }
        }
    }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    fun toggleRegistration(): Job? {
        val current = _uiState.value.allowRegistration ?: return null
        return viewModelScope.launch {
            try {
                val result = apiProvider.get().adminSetServerSettings(ServerSettingsDto(!current))
                _uiState.update { it.copy(allowRegistration = result.allowRegistration, error = null) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message ?: "Couldn't update") }
            } catch (e: IOException) {
                _uiState.update { it.copy(error = "You're offline") }
            }
        }
    }

    fun resetPassword(user: AdminUserDto): Job? {
        val pw = _uiState.value.password
        if (pw.isBlank()) {
            _uiState.update { it.copy(error = "Enter your password first") }
            return null
        }
        return viewModelScope.launch {
            try {
                val result = apiProvider.get().adminResetPassword(user.id, AdminPasswordRequest(pw))
                _uiState.update {
                    it.copy(resetEmail = user.email, resetPassword = result.password, error = null)
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message ?: "Couldn't reset password") }
            } catch (e: IOException) {
                _uiState.update { it.copy(error = "You're offline") }
            }
        }
    }

    fun deleteUser(user: AdminUserDto): Job? {
        val pw = _uiState.value.password
        if (pw.isBlank()) {
            _uiState.update { it.copy(error = "Enter your password first") }
            return null
        }
        return viewModelScope.launch {
            try {
                apiProvider.get().adminDeleteUser(user.id, AdminPasswordRequest(pw))
                _uiState.update {
                    it.copy(users = it.users.filterNot { u -> u.id == user.id }, error = null)
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message ?: "Couldn't delete user") }
            } catch (e: IOException) {
                _uiState.update { it.copy(error = "You're offline") }
            }
        }
    }
}
