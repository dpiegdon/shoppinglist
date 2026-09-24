package org.p23q.shoppinglist.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.account.CurrentAccount
import javax.inject.Inject

/**
 * Backs the app-root collector in [ShoppingListNavHost]: exposes the forced logout of the current
 * account and the local sign-out that must run before routing back to Login, plus the
 * update-required state that blocks the whole UI (T-240).
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    sessions: AccountSessions,
    private val currentAccount: CurrentAccount,
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** The server rejected the current account's token. Another account's 401 is not this screen's. */
    val forcedLogout: Flow<Unit> = sessions.forcedLogout.filter { it == currentAccount.localId }.map { }

    /** This app is too old for its server (T-240): the root shows nothing else while it is set. */
    val updateRequired: StateFlow<Boolean> =
        sessions.updateRequired.stateIn(viewModelScope, SharingStarted.Eagerly, sessions.isUpdateRequired())

    /** Signs the current account out locally (its token is already dead); returns the Job so callers can await it. */
    fun onForcedLogout(): Job = viewModelScope.launch {
        currentAccount.localId?.let { authRepository.clearLocalSession(it) }
    }
}
