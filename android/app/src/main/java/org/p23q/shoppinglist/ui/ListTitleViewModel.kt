package org.p23q.shoppinglist.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.data.ListAccounts
import org.p23q.shoppinglist.core.db.AccountEntity
import javax.inject.Inject

/**
 * The current list's name, observed live, for the top-bar title on the list / registry / list-props
 * destinations (T-34). Those screens sit inside [AppDrawerScaffold]'s content lambda, so the name
 * their own ViewModels know can't be read at the scaffold call site; this tiny ViewModel — scoped to
 * the same nav back-stack entry — provides it to the scaffold's title, and updates on rename/sync.
 */
@HiltViewModel
class ListTitleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    listsRepo: ListsRepo,
    listAccounts: ListAccounts,
    private val lastOpened: LastOpenedListStore,
) : ViewModel() {
    private val listId: String = checkNotNull(savedStateHandle[Routes.LIST_ID_ARG])

    /**
     * The list screen is showing this list: it is the one to reopen on the next launch (Notes), and
     * the New-list dialog's account follows it (T-292). Called by the list destination, the one
     * place every way of opening a list passes through (T-300): a card, a redeem, a notification, a
     * duplicate.
     */
    fun opened() {
        lastOpened.lastOpenedListId = listId
    }

    val name: StateFlow<String> = listsRepo.observeById(listId)
        .map { it?.name?.value ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * The account the app-bar subtitle names (T-292), when the phone holds more than one; null (no
     * subtitle, the screen as it always was) with one. The screen names it as [accountLineText]
     * does: email and server, or the local area's name in the app's language (T-293).
     */
    val subtitleAccount: StateFlow<AccountEntity?> = combine(listAccounts.observeAccount(listId), listAccounts.several) { account, several ->
        account?.takeIf { several }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The list's kind, for the nav host to pick the right screen (T-154). Null until the row is
     * known: an expenses list rendered as a shopping list for one frame would flash the wrong
     * screen on every visit, so the caller waits rather than guessing.
     */
    val kind: StateFlow<String?> = listsRepo.observeById(listId)
        .map { it?.kind?.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
