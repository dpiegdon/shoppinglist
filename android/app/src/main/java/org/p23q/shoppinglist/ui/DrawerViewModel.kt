package org.p23q.shoppinglist.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AccountEntity
import javax.inject.Inject

/** What the menu drawer and top bar need to know about the accounts: which servers "Server admin" offers, and whether any syncs. */
@HiltViewModel
class DrawerViewModel @Inject constructor(registry: AccountRegistry) : ViewModel() {

    /**
     * The signed-in admin accounts in the user's order (T-220, T-292, T-307): none, no Server admin
     * entry; one, the entry opens its console; several, it asks which. An affordance only: the
     * server enforces admin on every /admin route.
     */
    val adminAccounts: StateFlow<List<AccountEntity>> =
        registry.accounts.map(::admins).stateIn(viewModelScope, SharingStarted.Eagerly, admins(registry.snapshot()))

    /** Whether the phone holds a server account: with only the local area there is no sync to show (T-293). */
    val hasServer: StateFlow<Boolean> =
        registry.accounts.map { accounts -> accounts.any { it.isServer } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, registry.snapshot().any { it.isServer })

    private fun admins(accounts: List<AccountEntity>): List<AccountEntity> =
        accounts.sortedBy { it.sortOrder }.filter { it.isServer && it.signedIn && it.isAdmin }
}
