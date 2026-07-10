package org.p23q.shoppinglist.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.AuthRepository
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.api.ApiException
import org.p23q.shoppinglist.data.api.UnauthorizedException
import org.p23q.shoppinglist.ui.authedStartDestination
import java.io.IOException
import java.net.URI
import javax.inject.Inject

data class LoginUiState(
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
    val isRegisterMode: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val loginSucceeded: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val serverConfig: ServerConfig,
    private val sessionState: SessionState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // Prefill the previously-entered server URL (it persists in ServerConfig, but nothing
        // seeded the field before, so a returning user re-typed it every time). Only fill while
        // the field is still untouched, so we never clobber something the user is typing.
        viewModelScope.launch {
            val savedUrl = serverConfig.serverUrl.first()
            if (!savedUrl.isNullOrBlank()) {
                _uiState.update { if (it.serverUrl.isBlank()) it.copy(serverUrl = savedUrl) else it }
            }
        }
    }

    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value, errorMessage = null) }
    }

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onToggleRegisterMode() {
        _uiState.update { it.copy(isRegisterMode = !it.isRegisterMode, errorMessage = null) }
    }

    /** Returns the launched Job (or null if validation failed synchronously) so tests can await it. */
    fun submit(): Job? {
        val state = _uiState.value
        if (!isValidHttpsUrl(state.serverUrl)) {
            _uiState.update { it.copy(errorMessage = "Enter a valid https server URL") }
            return null
        }
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Email and password are required") }
            return null
        }

        return viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                serverConfig.setServerUrl(state.serverUrl)
                if (state.isRegisterMode) {
                    authRepository.register(state.email, state.password)
                }
                authRepository.login(state.email, state.password)
                _uiState.update { it.copy(isLoading = false, loginSucceeded = true) }
            } catch (e: UnauthorizedException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Incorrect email or password") }
            } catch (e: ApiException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Something went wrong") }
            } catch (e: IOException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Couldn't reach the server") }
            }
        }
    }

    /** Called from the menu (A6) — clears local session/mirror and returns to login regardless of network state. */
    fun logout(): Job = viewModelScope.launch { authRepository.logout() }

    fun startDestinationAfterLogin(): String =
        authedStartDestination(authRepository.lastOpenedListId())

    /** Notes "user info": the drawer (A6) shows this alongside the Log out entry. */
    val loggedInEmail: String? get() = sessionState.accountEmail
}

private fun isValidHttpsUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && !uri.host.isNullOrBlank()
}
