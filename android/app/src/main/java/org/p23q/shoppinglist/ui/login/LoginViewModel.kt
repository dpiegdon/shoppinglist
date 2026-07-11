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
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.api.ApiException
import org.p23q.shoppinglist.data.api.UnauthorizedException
import org.p23q.shoppinglist.data.sync.SyncTrigger
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.authedStartDestination
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.net.ssl.SSLException

data class LoginUiState(
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
    val isRegisterMode: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val loginSucceeded: Boolean = false,
    /** Debug-only self-signed-cert opt-in, surfaced here (not just in Settings) so a self-hoster can
     *  reach it before they've managed to log in — otherwise it's a bootstrap deadlock (T-38/T-46). */
    val allowSelfSignedCerts: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val serverConfig: ServerConfig,
    private val sessionState: SessionState,
    private val pendingInviteHolder: PendingInviteHolder,
    private val syncTrigger: SyncTrigger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // Prefill the previously-entered server URL (it persists in ServerConfig, but nothing
        // seeded the field before, so a returning user re-typed it every time). Only fill while
        // the field is still untouched, so we never clobber something the user is typing.
        // One coroutine reads both persisted values (mirrors SettingsViewModel). Two separate init
        // coroutines each doing a DataStore read let the second one resume on Main after a test had
        // already reset its dispatcher — crashing teardown; a single read avoids that.
        viewModelScope.launch {
            val savedUrl = serverConfig.serverUrl.first()
            val allowSelfSigned = serverConfig.allowSelfSignedCerts.first()
            _uiState.update {
                it.copy(
                    // Only prefill while the field is untouched, so we never clobber what the user types.
                    serverUrl = if (it.serverUrl.isBlank() && !savedUrl.isNullOrBlank()) savedUrl else it.serverUrl,
                    allowSelfSignedCerts = allowSelfSigned,
                )
            }
        }
    }

    /** Debug-only: persist the self-signed-cert opt-in (ApiProvider rebuilds its client on the flag,
     *  so the next submit picks it up). Surfaced on login to break the self-hosting bootstrap
     *  deadlock — the Settings screen isn't reachable until you're already logged in (T-38/T-46). */
    fun setAllowSelfSignedCerts(allow: Boolean): Job = viewModelScope.launch {
        serverConfig.setAllowSelfSignedCerts(allow)
        _uiState.update { it.copy(allowSelfSignedCerts = allow, errorMessage = null) }
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
                // The session is now authenticated — pull its data right away, so the first screen
                // isn't stuck on empty until some later incidental sync (the app-foreground sync
                // already fired before login, with no token).
                syncTrigger.scheduleImmediate()
                _uiState.update { it.copy(isLoading = false, loginSucceeded = true) }
            } catch (e: UnauthorizedException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Incorrect email or password") }
            } catch (e: ApiException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Something went wrong") }
            } catch (e: SSLException) {
                // Distinct from the generic reach-the-server case: an untrusted/self-signed cert is the
                // first thing a self-hoster hits, and it's actionable (T-38). SSLException extends
                // IOException, so this catch must come first.
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "The server's certificate isn't trusted. Use a certificate from a " +
                            "trusted CA (e.g. via a reverse proxy), or install your own CA on this device. " +
                            "For a self-signed dev server, enable \"Trust self-signed certificates\" in " +
                            "Settings (debug builds only).",
                    )
                }
            } catch (e: IOException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Couldn't reach the server") }
            }
        }
    }

    /** Called from the menu (A6) — clears local session/mirror and returns to login regardless of network state. */
    fun logout(): Job = viewModelScope.launch { authRepository.logout() }

    fun startDestinationAfterLogin(): String {
        // A logged-out invite (App Link / pasted code) was parked before login — resume straight
        // into redeeming it, rather than the usual overview/last-list (T-28).
        pendingInviteHolder.consume()?.let { token -> return Routes.redeem(token) }
        return authedStartDestination(authRepository.lastOpenedListId())
    }

    /** Notes "user info": the drawer (A6) shows this alongside the Log out entry. */
    val loggedInEmail: String? get() = sessionState.accountEmail
}

private fun isValidHttpsUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && !uri.host.isNullOrBlank()
}
