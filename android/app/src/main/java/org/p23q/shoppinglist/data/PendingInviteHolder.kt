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

    /** [accountId] is the local id of the account the invite was opened for, if the app had one. */
    fun stash(token: String, accountId: String?) {
        pending = PendingInvite(token, accountId)
    }

    /** Returns the pending invite (if any) and clears it. */
    fun consume(): PendingInvite? {
        val invite = pending
        pending = null
        return invite
    }
}

/** An invite token parked across a login, and the account it was opened for. */
data class PendingInvite(val token: String, val accountId: String?)
