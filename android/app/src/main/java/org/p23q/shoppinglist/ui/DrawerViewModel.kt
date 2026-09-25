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

/** What the menu drawer and top bar need to know about the accounts: whose server "Server admin" opens, and whether any syncs. */
@HiltViewModel
class DrawerViewModel @Inject constructor(registry: AccountRegistry) : ViewModel() {

    /**
     * The first signed-in admin account in the user's order (T-220, T-292), or null for no Server
     * admin entry. An affordance only: the server enforces admin on every /admin route.
     */
    val adminAccountId: StateFlow<String?> =
        registry.accounts.map(::firstAdmin).stateIn(viewModelScope, SharingStarted.Eagerly, firstAdmin(registry.snapshot()))

    /** Whether the phone holds a server account: with only the local area there is no sync to show (T-293). */
    val hasServer: StateFlow<Boolean> =
        registry.accounts.map { accounts -> accounts.any { it.isServer } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, registry.snapshot().any { it.isServer })

    private fun firstAdmin(accounts: List<AccountEntity>): String? =
        accounts.sortedBy { it.sortOrder }.firstOrNull { it.isServer && it.signedIn && it.isAdmin }?.id
}
