package org.p23q.shoppinglist.ui.accounts

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
import org.p23q.shoppinglist.core.LocalAreaNotEmptyException
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.api.Api
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.api.ChangeEmailRequest
import org.p23q.shoppinglist.core.api.ChangePasswordRequest
import org.p23q.shoppinglist.core.api.DeleteAccountRequest
import org.p23q.shoppinglist.core.api.SessionDto
import org.p23q.shoppinglist.core.api.UnauthorizedException
import org.p23q.shoppinglist.core.api.UpdateSettingsRequest
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.UiText
import java.io.IOException
import javax.inject.Inject

/** Where the Account screen goes once its account has left this phone. */
enum class AccountGone {
    /** Other accounts remain, the local area included: back to the Accounts screen. */
    TO_ACCOUNTS,

    /** It was the last account of any kind: the start screen, with nothing behind it. */
    TO_START,
}

data class AccountUiState(
    val account: AccountEntity? = null,
    val defaultCurrency: String = "",
    /**
     * Resolved default-or-override (T-64). `null` until the one-time fetch resolves (or when it
     * failed): the field then renders empty without that looking like a value the user chose.
     *
     * This is the server's *resolved* value: for an account with no override it is the
     * email-derived default, indistinguishable here from a stored one. That is why only
     * [AccountViewModel.updateInitials] ever sends it, as a deliberate user action (T-103).
     */
    val initials: String? = null,
    val sessions: List<SessionDto> = emptyList(),
    val currentPassword: String = "",
    val newPassword: String = "",
    /**
     * The new password typed a second time (T-313), compared here only: the request still carries
     * the one new password. [newPasswordMismatch] is set when they differ on submit, and typing in
     * either field clears it.
     */
    val newPasswordAgain: String = "",
    val newPasswordMismatch: Boolean = false,
    val newEmail: String = "",
    val changeEmailPassword: String = "",
    val deleteAccountPassword: String = "",
    val isDeleteConfirmOpen: Boolean = false,
    /** The "Remove from this phone" dialog; [unpushedCount] is counted when it opens. */
    val isRemoveConfirmOpen: Boolean = false,
    /** Rows of this account that never reached its server: pending and quarantined alike. */
    val unpushedCount: Int = 0,
    val errorMessage: UiText? = null,
    val infoMessage: UiText? = null,
    /** Set once the account is gone from this phone, deleted on its server or removed here. */
    val gone: AccountGone? = null,
    /** How many lists the account holds here; the local area can be removed only at 0 (T-293). */
    val listCount: Int? = null,
    /** Whether the phone holds the local area, which a list to keep can be copied to (T-294). */
    val hasLocalArea: Boolean = false,
    /** The local area's removal was refused: it held a list after all (T-302). */
    val removeBlocked: Boolean = false,
)

/**
 * One account's own settings (T-292), moved out of Settings: its server and email, default
 * currency, initials, password, email, sessions and the dev-only certificate opt-in, and the two
 * ways to part with it. Every request goes to this account's server with this account's token.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: AccountRegistry,
    private val sessions: AccountSessions,
    private val authRepository: AuthRepository,
    private val db: AppDb,
) : ViewModel() {

    val accountId: String = checkNotNull(savedStateHandle[Routes.ACCOUNT_ID_ARG])

    private val _uiState = MutableStateFlow(
        AccountUiState(account = registry.get(accountId), defaultCurrency = registry.get(accountId)?.defaultCurrency ?: ""),
    )
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        // The row follows the registry: a sign-in, an outdated server or a changed email shows up.
        viewModelScope.launch {
            registry.accounts.collect { accounts ->
                val account = accounts.firstOrNull { it.id == accountId } ?: return@collect
                _uiState.update { it.copy(account = account, hasLocalArea = accounts.any { other -> !other.isServer }) }
            }
        }
        viewModelScope.launch {
            db.listDao().activeLists().collect { lists ->
                _uiState.update { it.copy(listCount = lists.count { list -> list.accountId == accountId }) }
            }
        }
    }

    /**
     * Whether "Remove from this phone" may go ahead: always for a server account, whose lists stay
     * on its server; for the local area only once it holds no lists, which would be gone for good
     * (T-293). Its lists are deleted one by one first.
     */
    private fun removable(state: AccountUiState): Boolean =
        state.account?.isServer != false || state.listCount == 0

    private fun api(): Api = sessions.get(accountId).api

    /** A real request, so the screen asks once on open rather than on every construction. */
    fun loadSessions(): Job = viewModelScope.launch {
        try {
            val list = api().sessions().sessions
            _uiState.update { it.copy(sessions = list) }
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_msg_sessions_failed)) }
        }
    }

    /**
     * Best-effort, like [loadSessions]: until it resolves the initials field is just blank. The
     * same answer carries the default currency, which is stored on the row (T-300): the one read
     * at sign-in may have failed, and an initials save sends the currency back.
     */
    fun loadInitials(): Job = viewModelScope.launch {
        try {
            val settings = api().getSettings()
            registry.update(accountId) { it.copy(defaultCurrency = settings.defaultCurrency) }
            _uiState.update { it.copy(initials = settings.initials, defaultCurrency = settings.defaultCurrency) }
        } catch (e: IOException) {
            // Offline, signed out or refused (an ApiException is an IOException): the field stays blank.
        }
    }

    fun onCurrentPasswordChange(value: String) = _uiState.update { it.copy(currentPassword = value, errorMessage = null) }

    fun onNewPasswordChange(value: String) =
        _uiState.update { it.copy(newPassword = value, newPasswordMismatch = false, errorMessage = null) }

    fun onNewPasswordAgainChange(value: String) =
        _uiState.update { it.copy(newPasswordAgain = value, newPasswordMismatch = false, errorMessage = null) }

    fun onNewEmailChange(value: String) = _uiState.update { it.copy(newEmail = value, errorMessage = null) }

    fun onChangeEmailPasswordChange(value: String) = _uiState.update { it.copy(changeEmailPassword = value, errorMessage = null) }

    fun onDeleteAccountPasswordChange(value: String) = _uiState.update { it.copy(deleteAccountPassword = value, errorMessage = null) }

    fun onInitialsChange(value: String) = _uiState.update { it.copy(initials = value, errorMessage = null) }

    fun updateCurrency(currency: String): Job? {
        val normalized = currency.trim().uppercase()
        if (!ISO_CURRENCY.matches(normalized)) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_msg_currency_invalid)) }
            return null
        }
        return viewModelScope.launch {
            try {
                // Never send initials from the currency save (T-103): the server treats an absent
                // key as "leave unchanged" (T-87), and echoing the resolved value back would pin
                // the email-derived default as an explicit override.
                val response = api().updateSettings(UpdateSettingsRequest(normalized, initials = null))
                // An already-open list of this account follows it through its row.
                registry.update(accountId) { it.copy(defaultCurrency = response.defaultCurrency) }
                _uiState.update {
                    it.copy(
                        defaultCurrency = response.defaultCurrency,
                        initials = response.initials,
                        errorMessage = null,
                        infoMessage = UiText.res(R.string.settings_msg_currency_updated),
                    )
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = ErrorText.of(e, R.string.settings_msg_currency_failed)) }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = UiText.res(R.string.error_offline)) }
            }
        }
    }

    fun updateInitials(initials: String): Job? {
        val normalized = initials.trim().uppercase()
        if (normalized.length > INITIALS_MAX_LENGTH) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_msg_initials_too_long, INITIALS_MAX_LENGTH)) }
            return null
        }
        return viewModelScope.launch {
            try {
                val response = api().updateSettings(UpdateSettingsRequest(_uiState.value.defaultCurrency, normalized))
                _uiState.update {
                    it.copy(initials = response.initials, errorMessage = null, infoMessage = UiText.res(R.string.settings_msg_initials_updated))
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = ErrorText.of(e, R.string.settings_msg_initials_failed)) }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = UiText.res(R.string.error_offline)) }
            }
        }
    }

    fun changePassword(): Job? {
        val state = _uiState.value
        if (state.currentPassword.isBlank() || state.newPassword.isBlank()) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_msg_password_fields_required)) }
            return null
        }
        if (state.newPassword != state.newPasswordAgain) {
            _uiState.update { it.copy(newPasswordMismatch = true) }
            return null
        }
        return viewModelScope.launch {
            try {
                api().changePassword(ChangePasswordRequest(state.currentPassword, state.newPassword))
                _uiState.update {
                    it.copy(currentPassword = "", newPassword = "", newPasswordAgain = "", errorMessage = null, infoMessage = UiText.res(R.string.settings_msg_password_changed))
                }
            } catch (e: UnauthorizedException) {
                _uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_msg_password_incorrect)) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = ErrorText.of(e, R.string.settings_msg_password_failed, mapOf("invalid_credentials" to R.string.settings_msg_password_incorrect))) }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = UiText.res(R.string.error_offline)) }
            }
        }
    }

    fun changeEmail(): Job? {
        val state = _uiState.value
        if (state.changeEmailPassword.isBlank() || state.newEmail.isBlank()) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_msg_email_fields_required)) }
            return null
        }
        return viewModelScope.launch {
            try {
                api().changeEmail(ChangeEmailRequest(state.changeEmailPassword, state.newEmail))
                registry.update(accountId) { it.copy(email = state.newEmail) }
                _uiState.update {
                    it.copy(
                        newEmail = "",
                        changeEmailPassword = "",
                        errorMessage = null,
                        infoMessage = UiText.res(R.string.settings_msg_email_changed),
                    )
                }
            } catch (e: UnauthorizedException) {
                _uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_msg_delete_password_incorrect)) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = ErrorText.of(e, R.string.settings_msg_email_failed, mapOf("invalid_credentials" to R.string.settings_msg_delete_password_incorrect))) }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = UiText.res(R.string.error_offline)) }
            }
        }
    }

    fun revokeSession(id: String): Job = viewModelScope.launch {
        try {
            api().revokeSession(id)
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_msg_revoke_failed)) }
        }
        loadSessions().join()
    }

    /**
     * Dev-only (the switch is gated to debug builds); release builds ignore the flag (DevCertTrust).
     * This account's alone: another server keeps its own.
     */
    fun setAllowSelfSignedCerts(allow: Boolean): Job = viewModelScope.launch {
        registry.update(accountId) { it.copy(allowSelfSignedCerts = allow) }
    }

    fun requestDeleteAccount() = _uiState.update { it.copy(isDeleteConfirmOpen = true) }

    fun cancelDeleteAccount() = _uiState.update { it.copy(isDeleteConfirmOpen = false, deleteAccountPassword = "") }

    /** Deletes the account on its server, then here: gone there, so gone on this phone too. */
    fun confirmDeleteAccount(): Job? {
        val password = _uiState.value.deleteAccountPassword
        if (password.isBlank()) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_msg_password_required)) }
            return null
        }
        return viewModelScope.launch {
            try {
                api().deleteAccount(DeleteAccountRequest(password))
                authRepository.removeAccount(accountId)
                _uiState.update { it.copy(isDeleteConfirmOpen = false, gone = whereNext()) }
            } catch (e: UnauthorizedException) {
                _uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_msg_delete_password_incorrect)) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = ErrorText.of(e, R.string.settings_msg_delete_failed, mapOf("invalid_credentials" to R.string.settings_msg_delete_password_incorrect))) }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = UiText.res(R.string.error_offline)) }
            }
        }
    }

    /** Opens the removal warning with the count of this account's rows that never went out. */
    fun requestRemove(): Job = viewModelScope.launch {
        if (!removable(_uiState.value)) return@launch
        // The empty local area has nothing to lose and nothing to warn of.
        if (_uiState.value.account?.isServer == false) {
            remove()
            return@launch
        }
        val unpushed = db.listDao().dirtyRowsForAccount(accountId).size +
            db.itemDao().dirtyRowsForAccount(accountId).size +
            db.listDao().blockedRowCountForAccount(accountId) +
            db.itemDao().blockedRowCountForAccount(accountId)
        _uiState.update { it.copy(isRemoveConfirmOpen = true, unpushedCount = unpushed) }
    }

    fun cancelRemove() = _uiState.update { it.copy(isRemoveConfirmOpen = false) }

    /**
     * Removes the account from this phone only: its lists, items, token and row. The server keeps
     * the account and every list, and adding the account again brings them back. Nothing is
     * copied anywhere first; the warning says how to keep a list.
     */
    fun confirmRemove(): Job = viewModelScope.launch {
        if (!removable(_uiState.value)) return@launch
        remove()
    }

    private suspend fun remove() {
        try {
            authRepository.removeAccount(accountId)
        } catch (e: LocalAreaNotEmptyException) {
            // A list arrived after the count this screen went by; the registry counts again.
            _uiState.update { it.copy(isRemoveConfirmOpen = false, removeBlocked = true) }
            return
        }
        _uiState.update { it.copy(isRemoveConfirmOpen = false, gone = whereNext()) }
    }

    private fun whereNext(): AccountGone =
        if (registry.snapshot().any { it.id != accountId }) AccountGone.TO_ACCOUNTS else AccountGone.TO_START

    private companion object {
        val ISO_CURRENCY = Regex("^[A-Z]{3}$")
        const val INITIALS_MAX_LENGTH = 3
    }
}
