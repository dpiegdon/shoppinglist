package org.p23q.shoppinglist.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.p23q.shoppinglist.core.account.AccountSessions
import javax.inject.Inject

/**
 * Backs the app root in [ShoppingListNavHost]: the update-required state that blocks the whole UI
 * once every server account is too old for this build (T-240, T-244).
 *
 * A token a server rejects is not this view model's business: the account's row turns signed out
 * and keeps its lists, and the Accounts screen and the overview offer to sign in again. Nothing
 * navigates.
 */
@HiltViewModel
class RootViewModel @Inject constructor(sessions: AccountSessions) : ViewModel() {

    /** Every server account is too old for this build: the root shows nothing else while it is set. */
    val updateRequired: StateFlow<Boolean> =
        sessions.updateRequired.stateIn(viewModelScope, SharingStarted.Eagerly, sessions.isUpdateRequired())
}
