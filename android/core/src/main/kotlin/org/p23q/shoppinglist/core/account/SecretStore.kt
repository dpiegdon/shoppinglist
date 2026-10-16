package org.p23q.shoppinglist.core.account

/**
 * Bearer tokens, one per account, keyed by the local account id ([org.p23q.shoppinglist.core.db.AccountEntity.id]).
 * Kept out of Room on purpose: :app backs this with the Keystore-encrypted preferences.
 *
 * Synchronous, because the API client's plugins read the token and report a 401 without suspending.
 */
interface SecretStore {
    fun token(accountId: String): String?

    /** Null removes the token. */
    fun setToken(accountId: String, token: String?)
}

/** The list the app reopens on a cold start. One per device, not per account. */
interface LastOpenedListStore {
    var lastOpenedListId: String?
}
