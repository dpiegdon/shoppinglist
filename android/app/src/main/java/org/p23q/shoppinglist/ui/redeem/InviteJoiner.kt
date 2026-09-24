package org.p23q.shoppinglist.ui.redeem

import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.api.RedeemInviteRequest
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.UiText
import java.io.IOException

/**
 * Joining a list with an invite token, for one account (T-300): the one path behind a tapped or
 * pasted link ([RedeemViewModel]) and the overview's Join
 * ([org.p23q.shoppinglist.ui.overview.OverviewViewModel]). The server redeems the token and names
 * the list by its server id; the list is pulled for that account, and what is opened is this phone's
 * row of it.
 */
internal class InviteJoiner(
    private val registry: AccountRegistry,
    private val sessions: AccountSessions,
    private val syncer: Syncer,
    private val listsRepo: ListsRepo,
) {
    suspend fun join(accountId: String, token: String): InviteJoin {
        // Removed meanwhile: there is no server left to ask.
        val account = registry.get(accountId)?.takeIf { it.isServer && it.serverUrl != null }
            ?: return InviteJoin.Failed(UiText.res(R.string.redeem_msg_failed))
        return try {
            val serverId = sessions.get(account.id).api.redeemInvite(RedeemInviteRequest(token)).listId
            syncer.syncJoined(account.id, serverId)
            // Without the row (the pull failed) there is nothing to open yet.
            listsRepo.localIdForServerId(account.id, serverId)?.let { InviteJoin.Joined(it) }
                ?: InviteJoin.Failed(UiText.res(R.string.error_offline))
        } catch (e: ApiException) {
            // Before IOException, which it extends (T-264).
            val message = ErrorText.of(e, R.string.redeem_msg_failed, mapOf("invalid_token" to R.string.api_error_invite_not_found))
            InviteJoin.Failed(message, refused = true)
        } catch (e: IOException) {
            InviteJoin.Failed(UiText.res(R.string.error_offline))
        }
    }
}

/** What [InviteJoiner.join] came to. */
internal sealed interface InviteJoin {
    /** Joined; [listId] is this phone's row of the list. */
    data class Joined(val listId: String) : InviteJoin

    /** Not joined, and why; [refused] when the server said no rather than could not be reached. */
    data class Failed(val message: UiText, val refused: Boolean = false) : InviteJoin
}
