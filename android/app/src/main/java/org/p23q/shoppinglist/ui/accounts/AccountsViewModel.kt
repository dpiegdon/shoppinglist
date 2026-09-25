package org.p23q.shoppinglist.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.sync.SyncState
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.ui.overviewOrder
import javax.inject.Inject

/** What an account's row says about it, in the order the checks run. */
enum class AccountStatus {
    /** A device-local account (B3): it never syncs. */
    LOCAL,

    /** The server refused this build (426): the account's sync is paused until an update. */
    OUTDATED,

    /** The server rejected the token: the lists stay, and signing in again resumes the sync. */
    SIGNED_OUT,
    SIGNED_IN,
}

fun AccountEntity.status(): AccountStatus = when {
    !isServer -> AccountStatus.LOCAL
    outdated -> AccountStatus.OUTDATED
    !signedIn -> AccountStatus.SIGNED_OUT
    else -> AccountStatus.SIGNED_IN
}

/** One row of the Accounts screen: the account, what it is, and its own share of the sync status. */
data class AccountRow(
    val account: AccountEntity,
    val status: AccountStatus,
    val sync: SyncState,
)

/**
 * The Accounts screen (T-292): every account on this phone in the order the overview lists them
 * (server accounts in the user's order, the local area last), and that order.
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val registry: AccountRegistry,
    syncStatus: SyncStatus,
) : ViewModel() {

    val rows: StateFlow<List<AccountRow>> =
        combine(registry.accounts, syncStatus.accounts) { accounts, states ->
            overviewOrder(accounts).map { AccountRow(it, it.status(), states[it.id] ?: SyncState()) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether the phone has no local area yet, so the screen offers to add one (T-293). */
    val canAddLocal: StateFlow<Boolean> =
        registry.accounts.map { accounts -> accounts.none { !it.isServer } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, registry.snapshot().none { !it.isServer })

    private val _localNoteOpen = MutableStateFlow(false)

    /** The local area was just added here: its one-time note is showing. */
    val localNoteOpen: StateFlow<Boolean> = _localNoteOpen.asStateFlow()

    /** "Add local area": creates it, placed last, and shows its note once. */
    fun addLocal(): Job = viewModelScope.launch {
        if (registry.addLocal() != null) _localNoteOpen.value = true
    }

    fun dismissLocalNote() {
        _localNoteOpen.value = false
    }

    /** Swaps the server account with the one above it; the first stays where it is. */
    fun moveUp(id: String): Job = move(id, -1)

    /**
     * Swaps the server account with the one below it; the last server account stays where it is.
     * The local area is always last, so it never moves and nothing moves past it.
     */
    fun moveDown(id: String): Job = move(id, +1)

    private fun move(id: String, by: Int): Job = viewModelScope.launch {
        val ids = overviewOrder(registry.snapshot()).filter { it.isServer }.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + by
        if (from < 0 || to !in ids.indices) return@launch
        ids[from] = ids[to].also { ids[to] = id }
        registry.reorder(ids)
    }
}
