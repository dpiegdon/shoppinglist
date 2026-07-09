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
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.ui.Routes
import java.io.IOException
import javax.inject.Inject

data class ListPropsUiState(
    val name: String = "",
    val categoryOrder: List<String> = emptyList(),
    val members: List<MemberDto> = emptyList(),
    val pendingInvites: List<PendingInviteDto> = emptyList(),
    val isMembersLoading: Boolean = false,
    val membersError: String? = null,
    val inviteEmail: String = "",
    val inviteShareUrl: String? = null,
    val isLeaveConfirmOpen: Boolean = false,
    val hasLeft: Boolean = false,
    val errorMessage: String? = null,
)

/** Notes: rename, category order, members/invites, share, unsubscribe — all "list properties." */
@HiltViewModel
class ListPropsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listsRepo: ListsRepo,
    private val itemsRepo: ItemsRepo,
    private val apiProvider: ApiProvider,
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
            val allCategories = itemsRepo.distinctCategories(listId).first()
            _uiState.update {
                it.copy(name = list?.name?.value ?: "", categoryOrder = mergeCategoryOrder(currentOrder, allCategories))
            }
        }
    }

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

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

    fun saveName(): Job = viewModelScope.launch { listsRepo.rename(listId, _uiState.value.name.trim()) }

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
        runCatching { apiProvider.get().revokeInvite(inviteId) }
        loadMembers().join()
    }

    fun requestLeave() = _uiState.update { it.copy(isLeaveConfirmOpen = true) }

    fun cancelLeave() = _uiState.update { it.copy(isLeaveConfirmOpen = false) }

    /** Best-effort server call, same pattern as AuthRepositoryImpl.logout() — the device stops
     * syncing a list it no longer has access to either way, so local cleanup always happens.
     */
    fun confirmLeave(): Job = viewModelScope.launch {
        runCatching { apiProvider.get().leaveList(listId) }
        itemsRepo.hardDeleteByListId(listId)
        listsRepo.removeLocally(listId)
        _uiState.update { it.copy(isLeaveConfirmOpen = false, hasLeft = true) }
    }
}

private fun List<String>.swap(i: Int, j: Int): List<String> =
    toMutableList().apply { val tmp = this[i]; this[i] = this[j]; this[j] = tmp }

/**
 * Notes: "drag-reorder of distinctCategories ∪ current order" — a real union. A category
 * previously placed in the order stays there even if no item currently carries it (e.g. its last
 * item was recategorized); a category items actually use but that was never ordered gets appended
 * alphabetically.
 */
private fun mergeCategoryOrder(currentOrder: List<String>, allCategories: List<String>): List<String> {
    val leftover = allCategories.filterNot { it in currentOrder }.sortedBy { it.lowercase() }
    return currentOrder + leftover
}
