package org.p23q.shoppinglist.core

/**
 * Read/write session fields, kept separate from [TokenProvider] (interface segregation: OkHttp's
 * AuthInterceptor only ever needs read-only token access). AuthRepository depends on this instead
 * of the concrete [SessionStore] so it's fakeable in tests without a real Keystore.
 */
interface SessionState {
    var token: String?
    var accountEmail: String?
    /** The logged-in account's server id (T-65) — the basis for "changed by someone else" checks. */
    var accountId: String?
    /**
     * Which account the local mirror holds the lists of (T-260). Unlike [accountId] it outlives the
     * session: a forced logout leaves the mirror in place, unpushed edits and all, and this is what
     * the next login compares itself against to decide whether that mirror is the returning user's
     * own data or somebody else's to be wiped. Null means "not known", which is treated as
     * somebody else's.
     */
    var mirrorAccountId: String?
    /** Whether this account is a configured admin (T-107); from the login response. */
    var isAdmin: Boolean
    var defaultCurrency: String?
    var lastOpenedListId: String?
    var syncCursor: Long
    /**
     * Invites this device chose to ignore on the overview (T-233). A device-local choice, as in the
     * web client's browser storage: the invite sits greyed at the bottom, still joinable.
     */
    var ignoredInviteIds: Set<String>

    /** Wipes all session state, e.g. on logout. */
    fun clear()
}
