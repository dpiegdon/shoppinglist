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
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.data.SessionState
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
    /** Set when redeem was attempted without a session — the caller should route to Login (T-28). */
    val needsLogin: Boolean = false,
)

/** Notes: App Link / pasted token -> POST /invites/redeem -> syncNow(fullLists=[listId]) -> open list. */
@HiltViewModel
class RedeemViewModel @Inject constructor(
    private val apiProvider: ApiProvider,
    private val syncEngine: SyncEngine,
    private val sessionState: SessionState,
    private val pendingInviteHolder: PendingInviteHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RedeemUiState())
    val uiState: StateFlow<RedeemUiState> = _uiState.asStateFlow()

    fun onTokenChange(value: String) = _uiState.update { it.copy(token = value, errorMessage = null) }

    fun redeem(): Job? {
        val token = extractInviteToken(_uiState.value.token)
        if (token.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter an invite code") }
            return null
        }
        // Redeem needs a session. Logged out (e.g. tapped an invite link with no account signed in):
        // stash the token and signal the caller to send the user through Login, which resumes the
        // redeem afterwards — instead of a bare 401 that drops the invite (T-28).
        if (sessionState.token == null) {
            pendingInviteHolder.stash(token)
            _uiState.update { it.copy(needsLogin = true) }
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

/**
 * Accepts either a bare invite token or a full invite URL (T-71). Share links are
 * `<base_url>/invite/<token>` — possibly under a path prefix — so if the pasted text contains an
 * `/invite/` segment, take everything after the last one and strip any trailing slash, query, or
 * fragment that rode along. A bare token (no `/invite/`) is returned trimmed, unchanged.
 */
internal fun extractInviteToken(raw: String): String {
    val trimmed = raw.trim()
    val marker = "/invite/"
    val afterPrefix = trimmed.lastIndexOf(marker).let { idx ->
        if (idx >= 0) trimmed.substring(idx + marker.length) else trimmed
    }
    return afterPrefix.substringBefore('?').substringBefore('#').trimEnd('/')
}
