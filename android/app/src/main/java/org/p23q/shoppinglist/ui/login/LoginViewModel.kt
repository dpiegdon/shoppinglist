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
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.sync.SyncTrigger
import org.p23q.shoppinglist.data.AuthRepository
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.api.ApiException
import org.p23q.shoppinglist.data.api.UnauthorizedException
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.UiText
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
    val errorMessage: UiText? = null,
    val loginSucceeded: Boolean = false,
    /** Debug-only self-signed-cert opt-in, surfaced here (not just in Settings) so a self-hoster can
     *  reach it before they've managed to log in — otherwise it's a bootstrap deadlock (T-38/T-46). */
    val allowSelfSignedCerts: Boolean = false,
    /** Whether the configured server currently accepts new accounts (T-276); true until the
     *  up-front check says otherwise, so a slow or failed check never blocks registering. */
    val registrationAllowed: Boolean = true,
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
            val resolvedUrl = savedUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL
            _uiState.update {
                it.copy(
                    // Prefill the saved URL, or the canonical instance for a first run — and only
                    // while the field is untouched, so we never clobber what the user types.
                    serverUrl = when {
                        it.serverUrl.isNotBlank() -> it.serverUrl
                        else -> resolvedUrl
                    },
                    allowSelfSignedCerts = allowSelfSigned,
                )
            }
            // Only once a server has been saved, i.e. after the first login or register attempt
            // (T-287). On a fresh install the prefilled default is a suggestion nobody has confirmed,
            // and the app must not contact any server before the user has chosen one: if that
            // server turns out to refuse registration, the attempt failing with 403 is how they
            // find out — the same answer this check would have given, one screen later.
            if (!savedUrl.isNullOrBlank()) refreshRegistrationStatus()
        }
    }

    /** Asks the saved server up front whether it is accepting new accounts (T-276), matching the
     *  web login page. Best-effort — see [AuthRepository.registrationAllowed] — so any failure just
     *  leaves the toggle enabled rather than surfacing an error nobody asked about. Never called on
     *  a fresh install (T-287): see the init block. */
    fun refreshRegistrationStatus(): Job = viewModelScope.launch {
        val allowed = runCatching { authRepository.registrationAllowed() }.getOrDefault(true)
        _uiState.update { it.copy(registrationAllowed = allowed) }
    }

    private companion object {
        /** First-run prefill; still editable, and replaced by whatever the user last logged into. */
        const val DEFAULT_SERVER_URL = "https://p23q.org/shopping"
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
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.login_msg_invalid_url)) }
            return null
        }
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.login_msg_credentials_required)) }
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
                _uiState.update { it.copy(isLoading = false, errorMessage = UiText.res(R.string.login_msg_incorrect_credentials)) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = ErrorText.of(e, R.string.error_generic)) }
            } catch (e: SSLException) {
                // Distinct from the generic reach-the-server case: an untrusted/self-signed cert is the
                // first thing a self-hoster hits, and it's actionable (T-38). SSLException extends
                // IOException, so this catch must come first.
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiText.res(R.string.login_msg_untrusted_cert),
                    )
                }
            } catch (e: IOException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = UiText.res(R.string.error_offline)) }
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

    /** Whether to offer the drawer's Server admin entry (T-220). Read from the same session state
     *  as [loggedInEmail], so the drawer needs no second view model of its own. */
    val isAdmin: Boolean get() = sessionState.isAdmin
}

private fun isValidHttpsUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && !uri.host.isNullOrBlank()
}
