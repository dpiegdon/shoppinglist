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
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.account.CurrentAccount
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.api.RedeemInviteRequest
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.core.api.ApiSource
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.UiText
import java.io.IOException
import javax.inject.Inject

data class RedeemUiState(
    val token: String = "",
    val isLoading: Boolean = false,
    val errorMessage: UiText? = null,
    val redeemedListId: String? = null,
    /** Set when redeem was attempted without a session — the caller should route to Login (T-28). */
    val needsLogin: Boolean = false,
)

/**
 * Notes: App Link / pasted token -> POST /invites/redeem -> syncNow(fullLists=[listId]) -> open list.
 * The redemption names the list by its server id; what is opened is this phone's row of it.
 */
@HiltViewModel
class RedeemViewModel @Inject constructor(
    private val apiProvider: ApiSource,
    private val syncEngine: SyncEngine,
    private val currentAccount: CurrentAccount,
    private val pendingInviteHolder: PendingInviteHolder,
    private val listsRepo: ListsRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RedeemUiState())
    val uiState: StateFlow<RedeemUiState> = _uiState.asStateFlow()

    fun onTokenChange(value: String) = _uiState.update { it.copy(token = value, errorMessage = null) }

    fun redeem(): Job? {
        val token = extractInviteToken(_uiState.value.token)
        if (token.isBlank()) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.redeem_msg_code_required)) }
            return null
        }
        // Redeem needs a session. Logged out (e.g. tapped an invite link with no account signed in):
        // stash the token and signal the caller to send the user through Login, which resumes the
        // redeem afterwards — instead of a bare 401 that drops the invite (T-28).
        if (currentAccount.token == null) {
            pendingInviteHolder.stash(token, currentAccount.localId)
            _uiState.update { it.copy(needsLogin = true) }
            return null
        }
        return viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val serverId = apiProvider.get().redeemInvite(RedeemInviteRequest(token)).listId
                val accountId = currentAccount.localId
                syncEngine.syncNow(fullLists = listOf(serverId), fullListsAccountId = accountId)
                // Without the row (the pull failed) there is nothing to open yet.
                val listId = accountId?.let { listsRepo.localIdForServerId(it, serverId) } ?: run {
                    _uiState.update { it.copy(isLoading = false, errorMessage = UiText.res(R.string.error_offline)) }
                    return@launch
                }
                _uiState.update { it.copy(isLoading = false, redeemedListId = listId) }
            } catch (e: ApiException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = ErrorText.of(e, R.string.redeem_msg_failed, mapOf("invalid_token" to R.string.api_error_invite_not_found))) }
            } catch (e: IOException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = UiText.res(R.string.error_offline)) }
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
