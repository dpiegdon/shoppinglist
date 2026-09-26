package org.p23q.shoppinglist.ui.admin

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
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.api.Api
import org.p23q.shoppinglist.core.api.AdminPasswordRequest
import org.p23q.shoppinglist.core.api.AdminUserDto
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.api.ServerSettingsUpdate
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.UiText
import java.io.IOException
import javax.inject.Inject

data class AdminUiState(
    /** null until the list has been asked for (T-221); the console does not pull it on open. */
    val users: List<AdminUserDto>? = null,
    /** null = not loaded yet. */
    val allowRegistration: Boolean? = null,
    /**
     * The server message as the server holds it (T-315), "" for none; null = not loaded yet, or a
     * server too old to have one, for which the console shows no field.
     */
    val serverMessage: String? = null,
    /** What the message field holds; the loaded message until the admin types. */
    val messageDraft: String = "",
    /** Complaint shown AT the message field: the one-line rule, before anything is sent, or the server's 422. */
    val messageError: UiText? = null,
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

/**
 * Admin-only server console (T-107): registration toggle + reset/delete users, all with step-up.
 * One server's: the account the route names, on its server with its token (T-292).
 */
@HiltViewModel
class AdminViewModel internal constructor(
    private val api: suspend () -> Api,
    /** The server's id for the signed-in admin, whom the console never offers to delete. */
    serverAccountId: String?,
    /** The server this console administers, for its app bar (T-300): host and path, no scheme. */
    val server: String? = null,
) : ViewModel() {

    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        sessions: AccountSessions,
        registry: AccountRegistry,
    ) : this(
        api = { sessions.get(checkNotNull(savedStateHandle.get<String>(Routes.ACCOUNT_ID_ARG))).api },
        serverAccountId = savedStateHandle.get<String>(Routes.ACCOUNT_ID_ARG)?.let { registry.get(it)?.accountId },
        server = savedStateHandle.get<String>(Routes.ACCOUNT_ID_ARG)?.let { registry.get(it)?.serverUrl }?.let(::serverShown),
    )

    private val _uiState = MutableStateFlow(AdminUiState(currentAccountId = serverAccountId))
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    init {
        // Only the registration flag, which is one value. Pulling every account just to open the
        // console is the wrong default on an instance with hundreds of them (T-221).
        loadSettings()
    }

    fun loadSettings(): Job = viewModelScope.launch {
        try {
            val settings = api().adminGetServerSettings()
            _uiState.update {
                it.copy(
                    allowRegistration = settings.allowRegistration,
                    serverMessage = settings.message,
                    messageDraft = settings.message.orEmpty(),
                    error = null,
                )
            }
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
            val users = api().adminUsers().users
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
     * doing anything else — notably before opening the reset or delete confirmation, so the user
     * is never asked to confirm an action that then can't run (T-113, T-313).
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
                // The toggle sends only its own setting; the message is left as it is (T-315).
                val result = api().adminSetServerSettings(ServerSettingsUpdate(allowRegistration = !current))
                _uiState.update { it.copy(allowRegistration = result.allowRegistration, error = null) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = ErrorText.of(e, R.string.admin_msg_update_failed)) }
            } catch (e: IOException) {
                _uiState.update { it.copy(error = UiText.res(R.string.admin_msg_offline)) }
            }
        }
    }

    fun onMessageChange(value: String) = _uiState.update { it.copy(messageDraft = value, messageError = null) }

    /** Saves the field's text as the server message (T-315); checked here first, as the server checks it. */
    fun saveMessage(): Job? = sendMessage(_uiState.value.messageDraft.trim())

    /** Clear is saving "": no message. */
    fun clearMessage(): Job? = sendMessage("")

    private fun sendMessage(message: String): Job? {
        if (_uiState.value.serverMessage == null) return null
        if (!isValidServerMessage(message)) {
            _uiState.update { it.copy(messageError = UiText.res(R.string.admin_server_message_invalid)) }
            return null
        }
        return viewModelScope.launch {
            try {
                val result = api().adminSetServerSettings(ServerSettingsUpdate(message = message))
                _uiState.update {
                    it.copy(
                        allowRegistration = result.allowRegistration,
                        serverMessage = result.message ?: message,
                        messageDraft = result.message ?: message,
                        messageError = null,
                        error = null,
                    )
                }
            } catch (e: ApiException) {
                if (e.code == "invalid_message") {
                    _uiState.update { it.copy(messageError = UiText.res(R.string.admin_server_message_invalid)) }
                } else {
                    _uiState.update { it.copy(error = ErrorText.of(e, R.string.admin_msg_update_failed)) }
                }
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
                val result = api().adminResetPassword(user.id, AdminPasswordRequest(pw))
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
                api().adminDeleteUser(user.id, AdminPasswordRequest(pw))
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

/**
 * The server's rule for its message (T-315), applied to the already trimmed text: at most
 * [SERVER_MESSAGE_MAX] characters (code points, as the server counts them) and one line, with no
 * control character at all (Unicode category Cc). "" is valid: no message.
 */
internal fun isValidServerMessage(trimmed: String): Boolean =
    trimmed.codePointCount(0, trimmed.length) <= SERVER_MESSAGE_MAX &&
        trimmed.codePoints().noneMatch { Character.getType(it) == Character.CONTROL.toInt() }

internal const val SERVER_MESSAGE_MAX = 200

/** A server URL as the admin console's app bar shows it: `p23q.org/shopping`, no scheme or final slash. */
internal fun serverShown(serverUrl: String): String = serverUrl.substringAfter("://").removeSuffix("/")
