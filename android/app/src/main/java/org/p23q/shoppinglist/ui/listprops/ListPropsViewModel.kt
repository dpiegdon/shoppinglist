package org.p23q.shoppinglist.ui.listprops

import androidx.lifecycle.SavedStateHandle
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
import org.p23q.shoppinglist.core.CategoryCanon
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.NameOrder
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.api.CreateInviteRequest
import org.p23q.shoppinglist.core.api.MemberDto
import org.p23q.shoppinglist.core.api.PendingInviteDto
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.Syncer
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.UiText
import java.io.IOException
import javax.inject.Inject

data class ListPropsUiState(
    val name: String = "",
    /** "shopping" | "checklist" (T-110). */
    val kind: String = ListKind.DEFAULT,
    /** Free-text label, shown read-only: an expenses list keeps its currency for life (T-151). */
    val currency: String = "",
    /** Closing state (T-157), server-maintained: who has agreed, and when it closed. */
    val closeVotes: List<String> = emptyList(),
    val closedAt: Long? = null,
    val memberCount: Int = 0,
    val myAccountId: String? = null,
    val isVoting: Boolean = false,
    val categoryOrder: List<String> = emptyList(),
    val notes: String = "",
    val members: List<MemberDto> = emptyList(),
    val pendingInvites: List<PendingInviteDto> = emptyList(),
    val isMembersLoading: Boolean = false,
    val membersError: UiText? = null,
    val inviteEmail: String = "",
    val inviteShareUrl: String? = null,
    val isLeaveConfirmOpen: Boolean = false,
    val hasLeft: Boolean = false,
    /** A rename that would merge onto an existing category, awaiting confirmation (T-270): the
     *  web already confirms this destructive merge; Android used to do it silently on Save. */
    val pendingCategoryMerge: PendingCategoryMerge? = null,
    val duplicatedListId: String? = null,
    /** Per-list collaborator-change notifications (T-65); false = this list is muted. */
    val notificationsEnabledForList: Boolean = true,
    /** Number of checked items — drives the relocated 'Clear checked (N)' button (T-75). */
    val checkedCount: Int = 0,
    val errorMessage: UiText? = null,
)

/**
 * A rename held for confirmation. [fromName] is the category being renamed and [newName] what it
 * was renamed to, as given to [ListPropsViewModel.renameCategory]; [targetName] is the existing
 * category's own casing, for the dialog to name — not necessarily [newName]'s casing.
 *
 * The category is remembered by NAME, not by its row index: a sync can reorder or shrink the
 * category list while the dialog is open, and an index resolved then could point at a different
 * category than the one the user was asked about.
 */
data class PendingCategoryMerge(val fromName: String, val newName: String, val targetName: String)

/** Notes: rename, category order, members/invites, share, unsubscribe — all "list properties." */
@HiltViewModel
class ListPropsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listsRepo: ListsRepo,
    private val itemsRepo: ItemsRepo,
    private val apiProvider: ApiProvider,
    private val notificationPrefs: NotificationPrefsStore,
    private val sessionState: SessionState,
    private val syncer: Syncer,
) : ViewModel() {

    private val listId: String = checkNotNull(savedStateHandle[Routes.LIST_ID_ARG])

    private val _uiState = MutableStateFlow(ListPropsUiState())
    val uiState: StateFlow<ListPropsUiState> = _uiState.asStateFlow()

    init {
        // Local/DB-only, no network — members/invites are fetched separately via loadMembers(),
        // which the screen calls explicitly (Notes: "online-only with offline notice").
        viewModelScope.launch {
            val list = listsRepo.getById(listId)
            val currentOrder = list?.let { listsRepo.decodeCategoryOrder(it.categoryOrder.value) } ?: emptyList()
            val rawCategories = itemsRepo.categoryValues(listId).first()
            _uiState.update {
                it.copy(
                    name = list?.name?.value ?: "",
                    kind = ListKind.of(list?.kind?.value),
                    currency = list?.currency?.value.orEmpty(),
                    closeVotes = list?.let { row -> listsRepo.decodeCloseVotes(row.closeVotesJson) }
                        ?: emptyList(),
                    closedAt = list?.closedAt,
                    memberCount = list?.let { row -> listsRepo.decodeMembers(row.membersJson).size } ?: 0,
                    myAccountId = sessionState.accountId,
                    categoryOrder = buildCategoryDisplay(currentOrder, rawCategories),
                    notes = list?.notes?.value ?: "",
                )
            }
        }
        viewModelScope.launch {
            notificationPrefs.mutedListIds.collect { muted ->
                _uiState.update { it.copy(notificationsEnabledForList = listId !in muted) }
            }
        }
        // Live checked-item count for the relocated 'Clear checked' button (T-75); after a clear the
        // items move to backlog, the flow re-emits, and the count drops to 0 (hiding the button).
        viewModelScope.launch {
            itemsRepo.itemsForList(listId).collect { items ->
                val count = items.count { it.status.value == Status.CHECKED.wireValue }
                _uiState.update { it.copy(checkedCount = count) }
            }
        }
    }

    /** Per-list collaborator-notification mute (T-65) — device-local, deliberately not synced. */
    fun setListNotificationsEnabled(enabled: Boolean): Job = viewModelScope.launch {
        notificationPrefs.setListMuted(listId, muted = !enabled)
    }

    /**
     * The post-trip 'finish up' bulk action: move every checked item to backlog at once. Relocated
     * from the list screen to here (T-75) so it can't be tapped by accident while shopping — the
     * deliberate trip into list properties is the safeguard, so there's no undo snackbar.
     */
    fun clearChecked(): Job = viewModelScope.launch { itemsRepo.clearChecked(listId) }

    fun loadMembers(): Job = viewModelScope.launch {
        _uiState.update { it.copy(isMembersLoading = true, membersError = null) }
        try {
            val response = apiProvider.get().members(listId)
            _uiState.update {
                it.copy(members = response.members, pendingInvites = response.invites, isMembersLoading = false)
            }
        } catch (e: IOException) {
            _uiState.update { it.copy(isMembersLoading = false, membersError = UiText.res(R.string.listprops_msg_members_offline)) }
        }
    }

    /**
     * Convert between shopping list and checklist (T-110). Non-destructive: only changes which
     * fields the clients render — stores/price/quantity survive and reappear on switching back.
     */
    fun setKind(kind: String): Job = viewModelScope.launch {
        listsRepo.setKind(listId, kind)
        _uiState.update { it.copy(kind = ListKind.of(kind)) }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

    fun saveName(): Job = viewModelScope.launch { listsRepo.rename(listId, _uiState.value.name.trim()) }

    fun onNotesChange(value: String) = _uiState.update { it.copy(notes = value) }

    /** Blank collapses to null (matches how other optional text fields are stored) rather than an empty string. */
    fun saveNotes(): Job = viewModelScope.launch {
        listsRepo.setNotes(listId, _uiState.value.notes.trim().ifBlank { null })
    }

    fun moveCategoryUp(index: Int) {
        if (index <= 0) return
        _uiState.update { it.copy(categoryOrder = it.categoryOrder.swap(index, index - 1)) }
    }

    fun moveCategoryDown(index: Int) {
        val order = _uiState.value.categoryOrder
        if (index >= order.size - 1) return
        _uiState.update { it.copy(categoryOrder = it.categoryOrder.swap(index, index + 1)) }
    }

    fun saveCategoryOrder(): Job = viewModelScope.launch { listsRepo.setCategoryOrder(listId, _uiState.value.categoryOrder) }

    /**
     * Rename / recase a category (T-108): rewrite every item in it to [newNameRaw] and update the
     * category_order entry. A different word is a full rename; a case-only change fixes the casing.
     *
     * Renaming onto another existing category merges them (planRename de-dups the order) — every
     * item in both ends up in one, irreversibly. That is confirmed first (T-270), matching the web
     * client: this used to merge silently on Save.
     */
    fun renameCategory(index: Int, newNameRaw: String): Job? {
        val newName = newNameRaw.trim()
        val current = _uiState.value.categoryOrder
        if (index !in current.indices || newName.isBlank()) return null
        val fromKey = CategoryCanon.key(current[index])
        if (CategoryCanon.key(newName) == fromKey && newName == current[index]) return null // unchanged
        val toKey = CategoryCanon.key(newName)
        if (toKey != fromKey) {
            val target = current.firstOrNull { CategoryCanon.key(it) == toKey }
            if (target != null) {
                _uiState.update {
                    it.copy(pendingCategoryMerge = PendingCategoryMerge(current[index], newName, target))
                }
                return null
            }
        }
        return performRenameCategory(current[index], newName)
    }

    /**
     * The pending merge goes ahead, exactly as [renameCategory] would have without the guard —
     * unless the category it named has meanwhile disappeared (a sync removed or merged it while
     * the dialog was open), in which case there is nothing left to rename and it is dropped.
     */
    fun confirmCategoryMerge(): Job? {
        val pending = _uiState.value.pendingCategoryMerge ?: return null
        _uiState.update { it.copy(pendingCategoryMerge = null) }
        val fromKey = CategoryCanon.key(pending.fromName)
        if (_uiState.value.categoryOrder.none { CategoryCanon.key(it) == fromKey }) return null
        return performRenameCategory(pending.fromName, pending.newName)
    }

    fun cancelCategoryMerge() = _uiState.update { it.copy(pendingCategoryMerge = null) }

    private fun performRenameCategory(fromName: String, newName: String): Job {
        // Resolved NOW, by name, not carried over as an index from when the rename was requested:
        // the order can have changed underneath a confirmation dialog.
        val current = _uiState.value.categoryOrder
        val fromKey = CategoryCanon.key(fromName)
        return viewModelScope.launch {
            val items = itemsRepo.activeItemsForListOnce(listId)
            val plan = CategoryCanon.planRename(
                items.map { it.id to (it.category.value ?: "") },
                current,
                fromKey,
                newName,
            )
            itemsRepo.setCategoryBulk(plan.itemIds, newName)
            listsRepo.setCategoryOrder(listId, plan.nextCategoryOrder)
            _uiState.update { it.copy(categoryOrder = plan.nextCategoryOrder) }
        }
    }

    fun onInviteEmailChange(value: String) = _uiState.update { it.copy(inviteEmail = value, errorMessage = null) }

    fun sendInvite(): Job? {
        val email = _uiState.value.inviteEmail.trim()
        if (email.isBlank()) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.listprops_msg_email_required)) }
            return null
        }
        return viewModelScope.launch {
            try {
                val response = apiProvider.get().createInvite(listId, CreateInviteRequest(email))
                _uiState.update { it.copy(inviteEmail = "", inviteShareUrl = response.url, errorMessage = null) }
                loadMembers().join()
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = ErrorText.of(e, R.string.listprops_msg_invite_failed)) }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = UiText.res(R.string.error_offline)) }
            }
        }
    }

    fun consumeShareUrl() = _uiState.update { it.copy(inviteShareUrl = null) }

    fun revokeInvite(inviteId: String): Job = viewModelScope.launch {
        try {
            apiProvider.get().revokeInvite(inviteId)
        } catch (e: ApiException) {
            // 404: the invite is already gone — fall through and refresh so it drops off the list.
            if (e.httpStatus != 404) {
                _uiState.update { it.copy(errorMessage = ErrorText.of(e, R.string.listprops_msg_revoke_failed)) }
                return@launch
            }
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.error_offline_retry)) }
            return@launch
        }
        loadMembers().join()
    }

    /** Solo-owned snapshot copy of this list and its non-deleted items, purely client-side (T-63). */
    fun duplicateList(): Job = viewModelScope.launch {
        val newListId = listsRepo.duplicate(listId) ?: return@launch
        itemsRepo.duplicateForList(sourceListId = listId, targetListId = newListId)
        _uiState.update { it.copy(duplicatedListId = newListId) }
    }

    fun requestLeave() = _uiState.update { it.copy(isLeaveConfirmOpen = true) }

    fun cancelLeave() = _uiState.update { it.copy(isLeaveConfirmOpen = false) }

    /**
     * Leaving requires server confirmation (T-39): membership is not LWW content and has no retry
     * queue, so cleaning up locally on a failed/offline call would produce a "zombie" — the account
     * is still a member server-side, so the list reappears on the next full resync (and other members
     * still see you). Only remove local state after a confirmed success (or a 404 = already not a
     * member). NOTE: do NOT copy this to logout(), whose always-local-wipe is correct — you're ending
     * your own session there, not asking the server's permission.
     */
    /**
     * Agree to close this expenses list, or withdraw (T-158). Online-only, like leaving: whether
     * this was the last vote needed is the server's call, not a device's.
     */
    fun toggleCloseVote(): Job = viewModelScope.launch {
        val voted = _uiState.value.myAccountId in _uiState.value.closeVotes
        _uiState.update { it.copy(isVoting = true) }
        try {
            val api = apiProvider.get()
            if (voted) api.withdrawCloseVote(listId) else api.castCloseVote(listId)
            syncer.syncNow(emptyList())
        } catch (e: ApiException) {
            // A server refusal — 409 list_closed, 403 not_a_member, 409 not_an_expenses_list — has
            // a specific reason (T-264); ApiException must be caught before IOException, which it
            // extends, or every one of these shows as "couldn't reach the server" instead.
            _uiState.update { it.copy(errorMessage = ErrorText.of(e, R.string.expense_vote_failed)) }
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.error_offline_retry)) }
        } catch (e: IllegalStateException) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.expense_vote_failed)) }
        } finally {
            _uiState.update { it.copy(isVoting = false) }
        }
    }

    fun confirmLeave(): Job = viewModelScope.launch {
        try {
            apiProvider.get().leaveList(listId)
        } catch (e: ApiException) {
            // 404: the server already lacks the membership — effectively left, so finish cleanup.
            if (e.httpStatus != 404) {
                _uiState.update { it.copy(isLeaveConfirmOpen = false, errorMessage = ErrorText.of(e, R.string.listprops_msg_leave_failed)) }
                return@launch
            }
        } catch (e: IOException) {
            _uiState.update { it.copy(isLeaveConfirmOpen = false, errorMessage = UiText.res(R.string.error_offline_retry)) }
            return@launch
        }
        itemsRepo.hardDeleteByListId(listId)
        listsRepo.removeLocally(listId)
        _uiState.update { it.copy(isLeaveConfirmOpen = false, hasLeft = true) }
    }
}

private fun List<String>.swap(i: Int, j: Int): List<String> =
    toMutableList().apply { val tmp = this[i]; this[i] = this[j]; this[j] = tmp }

/**
 * The full category set for the settings panel (T-108): distinctCategories ∪ current order, keyed
 * case-insensitively so "Group"/"group" show once, in canonical casing. Ordered categories keep
 * their position (even if no item currently carries them); the rest are appended alphabetically.
 */
private fun buildCategoryDisplay(currentOrder: List<String>, rawCategories: List<String>): List<String> {
    // The same clean order the web shows and both clients save (T-212): first casing wins.
    val order = CategoryCanon.normalizeOrder(currentOrder)
    val names = CategoryCanon.canonicalNames(rawCategories, order)
    val orderedKeys = order.map(CategoryCanon::key)
    val leftover = names.keys.filter { it !in orderedKeys }
        .sortedWith(compareBy(NameOrder.names) { key: String -> names.getValue(key) }.thenBy { it })
    return (orderedKeys + leftover).mapNotNull { names[it] }
}
