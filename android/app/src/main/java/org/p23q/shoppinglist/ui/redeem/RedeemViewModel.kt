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
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.account.normalizeServerUrl
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.ui.login.LoginMode
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.UiText
import javax.inject.Inject

data class RedeemUiState(
    val token: String = "",
    val isLoading: Boolean = false,
    val errorMessage: UiText? = null,
    val redeemedListId: String? = null,
    /**
     * Set when the invite needs an account signed in first (T-28, T-292): the login route to open,
     * with the invite parked in [PendingInviteHolder] until the sign-in is through.
     */
    val needsLogin: String? = null,
    /** Several accounts could take this invite: the user picks one ("Join with which account?"). */
    val choices: List<AccountEntity> = emptyList(),
    /** The account the invite is being redeemed into, named on screen when the phone holds several. */
    val account: AccountEntity? = null,
    /** Whether the phone holds more than one account. */
    val several: Boolean = false,
)

/**
 * Notes: App Link / pasted token -> POST /invites/redeem -> sync the list -> open it.
 *
 * Which account redeems it (T-292): an invite link names its server, `<server URL>/invite/<token>`,
 * so the accounts whose server URL is exactly that prefix can take it; the host alone is not
 * enough, as two instances can share a host under different paths. One such account redeems it,
 * several ask which, none sends the user to sign in to that server first. A bare token names no
 * server: with one account it goes there, with several the user picks.
 *
 * The redemption itself is [InviteJoiner]'s, shared with the overview's Join.
 */
@HiltViewModel
class RedeemViewModel @Inject constructor(
    private val registry: AccountRegistry,
    private val sessions: AccountSessions,
    private val syncer: Syncer,
    private val pendingInviteHolder: PendingInviteHolder,
    private val listsRepo: ListsRepo,
) : ViewModel() {

    private val joiner = InviteJoiner(registry, sessions, syncer, listsRepo)

    private val _uiState = MutableStateFlow(RedeemUiState())
    val uiState: StateFlow<RedeemUiState> = _uiState.asStateFlow()

    /** The invite link the pending choice is for, when it came as one. */
    private var inviteUrl: String? = null

    fun onTokenChange(value: String) = _uiState.update { it.copy(token = value, errorMessage = null, choices = emptyList()) }

    /**
     * Redeems what [onTokenChange] was given. [link] is the invite link a tapped App Link came as
     * (a pasted one is read from the text itself); [accountId] names the account outright, as after
     * signing in for this invite.
     */
    fun redeem(link: String? = null, accountId: String? = null): Job? {
        val pasted = pastedInvite(_uiState.value.token)
        val token = extractInviteToken(pasted)
        if (token.isBlank()) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.redeem_msg_code_required)) }
            return null
        }
        val url = link ?: pasted.takeIf { inviteServerUrl(it) != null }
        inviteUrl = url
        val accounts = registry.snapshot()
        val servers = accounts.filter { it.isServer }
        _uiState.update { it.copy(several = accounts.size > 1) }
        val named = accountId?.let { id -> servers.firstOrNull { it.id == id } }
        if (named != null) return redeemInto(named, token)
        val candidates = inviteAccounts(servers, url)
        return when (candidates.size) {
            0 -> {
                // No account on that server (or none at all): sign in to it, then redeem (T-28).
                val mode = if (servers.isEmpty()) LoginMode.START else LoginMode.ADD
                pendingInviteHolder.stash(token, url, null, mode)
                _uiState.update { it.copy(needsLogin = Routes.login(mode, serverUrl = url?.let(::inviteServerUrl))) }
                null
            }
            1 -> redeemInto(candidates.single(), token)
            else -> {
                _uiState.update { it.copy(choices = candidates, errorMessage = null) }
                null
            }
        }
    }

    /** The screen has opened [RedeemUiState.redeemedListId]: a dialog opened again starts afresh. */
    fun redeemedListOpened() = _uiState.update { it.copy(redeemedListId = null) }

    /** The screen has opened [RedeemUiState.needsLogin]: a dialog opened again does not reopen it. */
    fun loginOpened() = _uiState.update { it.copy(needsLogin = null) }

    /** The user's answer to "Join with which account?". */
    fun chooseAccount(accountId: String): Job? {
        val account = _uiState.value.choices.firstOrNull { it.id == accountId } ?: return null
        _uiState.update { it.copy(choices = emptyList()) }
        return redeemInto(account, extractInviteToken(pastedInvite(_uiState.value.token)))
    }

    private fun redeemInto(account: AccountEntity, token: String): Job? {
        _uiState.update { it.copy(account = account, choices = emptyList()) }
        if (account.outdated) {
            _uiState.update { it.copy(errorMessage = UiText.res(R.string.overview_account_outdated)) }
            return null
        }
        // Signed out: park the invite and sign this account in again, which resumes the redeem
        // afterwards, instead of a bare 401 that drops the invite (T-28).
        if (!account.signedIn || !sessions.hasToken(account.id)) {
            pendingInviteHolder.stash(token, inviteUrl, account.id, LoginMode.RESIGNIN)
            _uiState.update { it.copy(needsLogin = Routes.login(LoginMode.RESIGNIN, accountId = account.id)) }
            return null
        }
        return viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = joiner.join(account.id, token)) {
                is InviteJoin.Joined -> _uiState.update { it.copy(isLoading = false, redeemedListId = result.listId) }
                is InviteJoin.Failed -> _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
            }
        }
    }
}

/**
 * The accounts that can take an invite: for a link, those whose server URL is the link's own
 * (everything before `/invite/`, in the canonical spelling); for a bare token, every one.
 */
internal fun inviteAccounts(servers: List<AccountEntity>, link: String?): List<AccountEntity> {
    val server = link?.let(::inviteServerUrl) ?: return servers
    return servers.filter { it.serverUrl == server }
}

/**
 * The server URL an invite link was shared from, canonical ([normalizeServerUrl]): everything
 * before its last `/invite/` segment. Null for text that is no link (a bare token).
 */
internal fun inviteServerUrl(link: String): String? {
    val trimmed = link.trim()
    if (!trimmed.contains("://")) return null
    val index = trimmed.lastIndexOf("/invite/")
    if (index < 0) return null
    return normalizeServerUrl(trimmed.substring(0, index + 1))
}

/**
 * The invite in pasted text (T-300): a whole message such as "Join my list: https://…/invite/T"
 * is pasted as readily as the link alone, so the first https URL in it, preferring one with an
 * `/invite/` segment, with any sentence punctuation after it dropped. Text with no https URL in it
 * (a bare token) is returned trimmed, unchanged.
 */
internal fun pastedInvite(raw: String): String {
    val urls = HTTPS_URL.findAll(raw).map { it.value.trimEnd(*URL_TRAILING_PUNCTUATION) }.toList()
    return urls.firstOrNull { it.contains("/invite/") } ?: urls.firstOrNull() ?: raw.trim()
}

private val HTTPS_URL = Regex("""https://\S+""", RegexOption.IGNORE_CASE)
private val URL_TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '>', '"', '\'')

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
