package org.p23q.shoppinglist.ui.login

import androidx.lifecycle.SavedStateHandle
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
import org.p23q.shoppinglist.core.AuthRepository
import kotlinx.serialization.SerializationException
import org.p23q.shoppinglist.core.AppTooOldException
import org.p23q.shoppinglist.core.NotATuppuServerException
import org.p23q.shoppinglist.core.ServerTooOldException
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.api.UnauthorizedException
import org.p23q.shoppinglist.core.sync.SyncTrigger
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.data.LastServerAddress
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.UiText
import org.p23q.shoppinglist.ui.authedStartDestination
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.net.ssl.SSLException

/** What the login form is for (T-292), from the route's `mode` argument. */
enum class LoginMode(val arg: String) {
    /** No server account on this phone yet: the start screen. */
    START("start"),

    /** Another account beside the ones already here, opened from the Accounts screen. */
    ADD("add"),

    /** An account the server signed out, signing in again: its server URL is fixed. */
    RESIGNIN("resignin");

    companion object {
        fun fromArg(arg: String?): LoginMode = entries.firstOrNull { it.arg == arg } ?: START
    }
}

data class LoginUiState(
    val mode: LoginMode = LoginMode.START,
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
    val isRegisterMode: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: UiText? = null,
    val loginSucceeded: Boolean = false,
    /** A re-sign-in's server is the account's own and cannot be changed here. */
    val serverUrlLocked: Boolean = false,
    /** Debug-only self-signed-cert opt-in, surfaced here (not just in Settings) so a self-hoster can
     *  reach it before they've managed to log in — otherwise it's a bootstrap deadlock (T-38/T-46). */
    val allowSelfSignedCerts: Boolean = false,
    /** Whether the configured server currently accepts new accounts (T-276); true until the
     *  up-front check says otherwise, so a slow or failed check never blocks registering. */
    val registrationAllowed: Boolean = true,
    /**
     * The app package the server offers, set with the "app too old" message (T-298): the server
     * speaks a newer protocol than this build, so an update is the only way in.
     */
    val downloadUrl: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val knownAccounts: KnownAccounts,
    private val serverConfig: LastServerAddress,
    private val pendingInviteHolder: PendingInviteHolder,
    private val syncTrigger: SyncTrigger,
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val mode = LoginMode.fromArg(savedStateHandle[Routes.LOGIN_MODE_ARG])

    /** The account a re-sign-in is for; null in the other modes, or if it has gone meanwhile. */
    private val resignInAccount: AccountEntity? =
        if (mode == LoginMode.RESIGNIN) {
            savedStateHandle.get<String>(Routes.ACCOUNT_ID_ARG)?.let { id -> knownAccounts.snapshot().firstOrNull { it.id == id } }
        } else {
            null
        }

    private val _uiState = MutableStateFlow(
        resignInAccount?.let { account ->
            LoginUiState(
                mode = mode,
                serverUrl = account.serverUrl.orEmpty(),
                email = account.email.orEmpty(),
                serverUrlLocked = true,
                allowSelfSignedCerts = account.allowSelfSignedCerts,
            )
        } ?: LoginUiState(
            mode = mode,
            // An invite link for a server this phone has no account on opens the add form on
            // that server (T-292).
            serverUrl = savedStateHandle.get<String>(Routes.SERVER_URL_ARG).orEmpty(),
        ),
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // A re-sign-in knows its server and its certificate choice already.
        if (resignInAccount == null) viewModelScope.launch {
            // Prefill the address last submitted on this device, kept whatever became of the
            // attempt or its account (T-298), or the canonical instance for a first run. A URL
            // the user has started typing in the meantime is left alone.
            val savedUrl = serverConfig.lastServerUrl()?.takeIf { it.isNotBlank() }
            val allowSelfSigned = serverConfig.lastAllowSelfSignedCerts()
            _uiState.update {
                it.copy(
                    serverUrl = it.serverUrl.ifBlank { savedUrl ?: DEFAULT_SERVER_URL },
                    allowSelfSignedCerts = allowSelfSigned,
                )
            }
            // Only once an address has been submitted (T-287). On a fresh install the prefilled
            // default is a suggestion nobody has confirmed, and the app must not contact any
            // server before the user has chosen one: if that server turns out to refuse
            // registration, the attempt failing with 403 is how they find out — the same answer
            // this check would have given, one screen later.
            if (savedUrl != null) refreshRegistrationStatus()
        }
    }

    /** Asks the saved server up front whether it is accepting new accounts (T-276), matching the
     *  web login page. Best-effort — see [AuthRepository.registrationAllowed] — so any failure just
     *  leaves the toggle enabled rather than surfacing an error nobody asked about. Never called on
     *  a fresh install (T-287): see the init block. */
    fun refreshRegistrationStatus(): Job = viewModelScope.launch {
        val url = serverConfig.lastServerUrl() ?: return@launch
        val allowed = runCatching {
            authRepository.registrationAllowed(url, _uiState.value.allowSelfSignedCerts)
        }.getOrDefault(true)
        _uiState.update { it.copy(registrationAllowed = allowed) }
    }

    private companion object {
        /** First-run prefill; still editable, and replaced by whatever the user last logged into. */
        const val DEFAULT_SERVER_URL = "https://p23q.org/shopping"
    }

    /** Debug-only: the self-signed-cert opt-in, which the next submit uses and the account it signs
     *  in to keeps. Surfaced on login to break the self-hosting bootstrap deadlock — the Settings
     *  screen isn't reachable until you're already logged in (T-38/T-46). */
    fun setAllowSelfSignedCerts(allow: Boolean): Job = viewModelScope.launch {
        _uiState.update { it.copy(allowSelfSignedCerts = allow, errorMessage = null) }
        serverConfig.setLastAllowSelfSignedCerts(allow)
    }

    fun onServerUrlChange(value: String) {
        if (_uiState.value.serverUrlLocked) return
        _uiState.update { it.copy(serverUrl = value, errorMessage = null, downloadUrl = null) }
    }

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, errorMessage = null, downloadUrl = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null, downloadUrl = null) }
    }

    fun onToggleRegisterMode() {
        _uiState.update { it.copy(isRegisterMode = !it.isRegisterMode, errorMessage = null, downloadUrl = null) }
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
            _uiState.update { it.copy(isLoading = true, errorMessage = null, downloadUrl = null) }
            try {
                // Before the server has answered, as in 3.1.0: the next visit prefills what was
                // typed whatever becomes of this attempt.
                serverConfig.setLastServerUrl(state.serverUrl)
                serverConfig.setLastAllowSelfSignedCerts(state.allowSelfSignedCerts)
                if (state.isRegisterMode) {
                    authRepository.register(state.serverUrl, state.email, state.password, state.allowSelfSignedCerts)
                }
                val before = knownAccounts.snapshot().associateBy { it.id }
                // Every other account stays (T-292). On the start screen there is none to keep,
                // and wherever else the form is opened, the accounts beside this one are the
                // user's own: nobody signs out here, so nobody else's lists are on the phone.
                val id = authRepository.login(
                    state.serverUrl,
                    state.email,
                    state.password,
                    state.allowSelfSignedCerts,
                    keepOtherAccounts = true,
                )
                if (mode == LoginMode.ADD && before[id]?.signedIn == true) {
                    // The same server and account as one already here and signed in: there is
                    // nothing to add. The sign-in only gave that account a fresh token.
                    _uiState.update { it.copy(isLoading = false, errorMessage = UiText.res(R.string.login_msg_already_added)) }
                    return@launch
                }
                // The session is now authenticated — pull its data right away, so the first screen
                // isn't stuck on empty until some later incidental sync (the app-foreground sync
                // already fired before login, with no token).
                syncTrigger.scheduleImmediate()
                _uiState.update { it.copy(isLoading = false, loginSucceeded = true) }
            } catch (e: ServerTooOldException) {
                // Asked before anything else went to a server this device did not know (T-291).
                _uiState.update { it.copy(isLoading = false, errorMessage = UiText.res(R.string.login_msg_server_too_old)) }
            } catch (e: AppTooOldException) {
                // The same question, the other way round (T-298): the sign-in would be refused
                // with 426, so offer the server's package, if it has one, right here.
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiText.res(R.string.login_msg_app_too_old),
                        downloadUrl = e.downloadUrl,
                    )
                }
            } catch (e: NotATuppuServerException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = UiText.res(R.string.login_msg_not_a_server)) }
            } catch (e: SerializationException) {
                // Any other answer that is not the API's JSON: the address is not a Tuppu server.
                _uiState.update { it.copy(isLoading = false, errorMessage = UiText.res(R.string.login_msg_not_a_server)) }
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

    /**
     * Where to go once signed in: into redeeming an invite parked before the sign-in (T-28), else
     * from the start screen to the lists as a cold start would, else null, back to where the form
     * was opened from (the Accounts screen, or a signed-out account's banner).
     */
    fun startDestinationAfterLogin(): String? {
        pendingInviteHolder.consume()?.let { invite -> return Routes.redeem(invite.token) }
        return if (mode == LoginMode.START) authedStartDestination(authRepository.lastOpenedListId()) else null
    }
}

private fun isValidHttpsUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && !uri.host.isNullOrBlank()
}
