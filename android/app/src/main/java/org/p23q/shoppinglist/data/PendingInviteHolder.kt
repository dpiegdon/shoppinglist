package org.p23q.shoppinglist.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries an invite token across a login (T-28). When a logged-out user opens an invite link (App
 * Link) or pastes a code, redeem can't run without a session; the token is stashed here, the user
 * is sent to Login, and [org.p23q.shoppinglist.ui.login.LoginViewModel] consumes it on success to
 * resume straight into the redeem flow rather than silently dropping the invite.
 *
 * In-memory (process-scoped): the flow is tap-link -> log in -> redeem within one app session, so
 * this needn't survive a process death (if it does, the user just re-opens the link). Held once
 * and consumed once.
 */
@Singleton
class PendingInviteHolder @Inject constructor() {
    private var pending: PendingInvite? = null

    /**
     * [accountId] is the local id of the account the invite is for, when that is settled (it only
     * needs signing in again); [url] the invite link it came as, which names its server (T-292).
     */
    fun stash(token: String, accountId: String?, url: String? = null) {
        pending = PendingInvite(token, accountId, url)
    }

    /** Returns the pending invite (if any) and clears it. */
    fun consume(): PendingInvite? {
        val invite = pending
        pending = null
        return invite
    }
}

/** An invite token parked across a login, the account it is for if known, and the link it came as. */
data class PendingInvite(val token: String, val accountId: String?, val url: String? = null)
