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
import org.p23q.shoppinglist.data.api.ApiException
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.CreateInviteRequest
import org.p23q.shoppinglist.data.api.MemberDto
import org.p23q.shoppinglist.data.api.PendingInviteDto
import org.p23q.shoppinglist.data.CategoryCanon
import org.p23q.shoppinglist.data.ListKind
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.ui.Routes
import java.io.IOException
import javax.inject.Inject

data class ListPropsUiState(
    val name: String = "",
    /** "shopping" | "checklist" (T-110). */
    val kind: String = ListKind.DEFAULT,
    val categoryOrder: List<String> = emptyList(),
    val notes: String = "",
    val members: List<MemberDto> = emptyList(),
    val pendingInvites: List<PendingInviteDto> = emptyList(),
    val isMembersLoading: Boolean = false,
    val membersError: String? = null,
    val inviteEmail: String = "",
    val inviteShareUrl: String? = null,
    val isLeaveConfirmOpen: Boolean = false,
    val hasLeft: Boolean = false,
    val duplicatedListId: String? = null,
    /** Per-list collaborator-change notifications (T-65); false = this list is muted. */
    val notificationsEnabledForList: Boolean = true,
    /** Number of checked items — drives the relocated 'Clear checked (N)' button (T-75). */
    val checkedCount: Int = 0,
    val errorMessage: String? = null,
)

/** Notes: rename, category order, members/invites, share, unsubscribe — all "list properties." */
@HiltViewModel
class ListPropsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listsRepo: ListsRepo,
    private val itemsRepo: ItemsRepo,
    private val apiProvider: ApiProvider,
    private val notificationPrefs: NotificationPrefsStore,
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
            _uiState.update { it.copy(isMembersLoading = false, membersError = "Members are only available online") }
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
     * Renaming onto another existing category merges them (planRename de-dups the order).
     */
    fun renameCategory(index: Int, newNameRaw: String): Job? {
        val newName = newNameRaw.trim()
        val current = _uiState.value.categoryOrder
        if (index !in current.indices || newName.isBlank()) return null
        val fromKey = CategoryCanon.key(current[index])
        if (CategoryCanon.key(newName) == fromKey && newName == current[index]) return null // unchanged
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
            _uiState.update { it.copy(errorMessage = "Enter an email address") }
            return null
        }
        return viewModelScope.launch {
            try {
                val response = apiProvider.get().createInvite(listId, CreateInviteRequest(email))
                _uiState.update { it.copy(inviteEmail = "", inviteShareUrl = response.url, errorMessage = null) }
                loadMembers().join()
            } catch (e: ApiException) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Couldn't send invite") }
            } catch (e: IOException) {
                _uiState.update { it.copy(errorMessage = "Couldn't reach the server") }
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
                _uiState.update { it.copy(errorMessage = e.message ?: "Couldn't revoke the invite") }
                return@launch
            }
        } catch (e: IOException) {
            _uiState.update { it.copy(errorMessage = "You're offline; try again when connected") }
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
    fun confirmLeave(): Job = viewModelScope.launch {
        try {
            apiProvider.get().leaveList(listId)
        } catch (e: ApiException) {
            // 404: the server already lacks the membership — effectively left, so finish cleanup.
            if (e.httpStatus != 404) {
                _uiState.update { it.copy(isLeaveConfirmOpen = false, errorMessage = e.message ?: "Couldn't leave the list") }
                return@launch
            }
        } catch (e: IOException) {
            _uiState.update { it.copy(isLeaveConfirmOpen = false, errorMessage = "You're offline; try again when connected") }
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
    val names = CategoryCanon.canonicalNames(rawCategories, currentOrder)
    val orderedKeys = currentOrder.map { CategoryCanon.key(it) }.filter { it.isNotEmpty() }.distinct()
    val leftover = names.keys.filter { it !in orderedKeys }.sortedBy { names.getValue(it).lowercase() }
    return (orderedKeys + leftover).mapNotNull { names[it] }
}
