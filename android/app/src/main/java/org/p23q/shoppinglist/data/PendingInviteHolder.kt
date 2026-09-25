package org.p23q.shoppinglist.data

import org.p23q.shoppinglist.ui.login.LoginMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries an invite token across a login (T-28). When an invite cannot be redeemed until an
 * account signs in (none on the link's server, or the one it is for is signed out), the token is
 * stashed here, the user is sent to the login form, and
 * [org.p23q.shoppinglist.ui.login.LoginViewModel] resumes the redeem once that sign-in is through.
 *
 * A parked invite belongs to the one login it was parked for (T-300): [consumeFor] hands it only to
 * a login in the same [LoginMode] and, for a re-sign-in, to the same account, and forgets it either
 * way; the login form calls [clear] when it is left without success. Otherwise an invite the user backed out of would take over the next, unrelated
 * sign-in.
 *
 * In-memory (process-scoped): the flow is tap-link -> log in -> redeem within one app session, so
 * this needn't survive a process death (if it does, the user just re-opens the link).
 */
@Singleton
class PendingInviteHolder @Inject constructor() {
    private var pending: PendingInvite? = null

    /**
     * Parks [token] for a login in [mode]. [url] is the invite link it came as, which names its
     * server, or null for a bare token; [accountId] the local id of the account it is for when that
     * is settled (a re-sign-in).
     */
    fun stash(token: String, url: String?, accountId: String?, mode: LoginMode) {
        pending = PendingInvite(token, accountId, url, mode)
    }

    /**
     * The parked invite if it was parked for this login, and nothing is parked afterwards either
     * way: the login is through, so an invite it does not take is stale. [accountId] is the local
     * id of the account the login went to.
     *
     * It matches when the mode is the one it was parked for and, for a re-sign-in, the account is
     * the one it was parked for. An added or first account takes it whatever server it signed in
     * to: the form was opened for this invite, the user may have corrected the prefilled address
     * to the server's real one, and a token from another server is refused by the server itself.
     */
    fun consumeFor(mode: LoginMode, accountId: String?): PendingInvite? {
        val invite = pending
        pending = null
        return invite?.takeIf { it.isFor(mode, accountId) }
    }

    /** Forgets the parked invite: the login it waited for was abandoned. */
    fun clear() {
        pending = null
    }
}

/**
 * An invite token parked across a login: the account it is for if known, the link it came as,
 * and the [mode] of the login it waits for.
 */
data class PendingInvite(
    val token: String,
    val accountId: String?,
    val url: String?,
    val mode: LoginMode,
) {
    internal fun isFor(mode: LoginMode, accountId: String?): Boolean {
        if (mode != this.mode) return false
        return when (mode) {
            LoginMode.RESIGNIN -> this.accountId != null && accountId == this.accountId
            LoginMode.START, LoginMode.ADD -> true
        }
    }
}
