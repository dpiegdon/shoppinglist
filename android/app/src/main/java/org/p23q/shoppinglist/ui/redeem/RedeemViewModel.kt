package org.p23q.shoppinglist.ui.redeem

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.api.ApiException
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.RedeemInviteRequest
import org.p23q.shoppinglist.data.sync.SyncEngine
import java.io.IOException
import javax.inject.Inject

data class RedeemUiState(
    val token: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val redeemedListId: String? = null,
)

/** Notes: App Link / pasted token -> POST /invites/redeem -> syncNow(fullLists=[listId]) -> open list. */
@HiltViewModel
class RedeemViewModel @Inject constructor(
    private val apiProvider: ApiProvider,
    private val syncEngine: SyncEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RedeemUiState())
    val uiState: StateFlow<RedeemUiState> = _uiState.asStateFlow()

    fun onTokenChange(value: String) = _uiState.update { it.copy(token = value, errorMessage = null) }

    fun redeem(): Job? {
        val token = _uiState.value.token.trim()
        if (token.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter an invite code") }
            return null
        }
        return viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val listId = apiProvider.get().redeemInvite(RedeemInviteRequest(token)).listId
                syncEngine.syncNow(fullLists = listOf(listId))
                _uiState.update { it.copy(isLoading = false, redeemedListId = listId) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Couldn't redeem invite") }
            } catch (e: IOException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Couldn't reach the server") }
            }
        }
    }
}
