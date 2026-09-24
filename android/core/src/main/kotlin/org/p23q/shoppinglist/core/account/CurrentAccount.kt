package org.p23q.shoppinglist.core.account

import org.p23q.shoppinglist.core.api.Api
import org.p23q.shoppinglist.core.api.ApiSource
import org.p23q.shoppinglist.core.db.AccountEntity

/**
 * The one account the single-account screens show: the first server account in the user's order.
 * A shim while the UI knows only one account; it goes once the screens take an account id of
 * their own.
 *
 * Every read is synchronous, from [AccountRegistry]'s in-memory copy, and says nothing (null,
 * false, empty) while there is no account or before the registry has loaded. Every write changes
 * that copy at once and is stored in the background.
 */
interface CurrentAccount {
    /** The local account id, or null before the first login. */
    val localId: String?
    val serverUrl: String?

    /** The bearer token, or null when there is no account or it is signed out. */
    val token: String?

    /** The server's id for the account (T-65), the basis for "changed by someone else" checks. */
    val accountId: String?
    var accountEmail: String?

    /** Whether this account is a configured admin (T-107); from the login response. */
    val isAdmin: Boolean
    var defaultCurrency: String?

    /**
     * Invites this device chose to ignore on the overview (T-233). A device-local choice, as in the
     * web client's browser storage: the invite sits greyed at the bottom, still joinable.
     */
    var ignoredInviteIds: Set<String>

    /** Debug builds only honour it; see DevCertTrust.kt in :app. */
    var allowSelfSignedCerts: Boolean

    /** The list the app reopens on a cold start; device-wide, not the account's. */
    var lastOpenedListId: String?
}

/** [CurrentAccount] over the registry, the secret store and the device's last-opened list. */
class RegistryCurrentAccount(
    private val registry: AccountRegistry,
    private val secrets: SecretStore,
    private val lastOpened: LastOpenedListStore,
) : CurrentAccount {
    private val account: AccountEntity? get() = registry.snapshot().firstOrNull { it.isServer }

    override val localId: String? get() = account?.id
    override val serverUrl: String? get() = account?.serverUrl

    override val token: String?
        get() = account?.takeIf { it.signedIn }?.let { secrets.token(it.id) }

    override val accountId: String? get() = account?.accountId

    override var accountEmail: String?
        get() = account?.email
        set(value) = change { it.copy(email = value) }

    override val isAdmin: Boolean get() = account?.isAdmin ?: false

    override var defaultCurrency: String?
        get() = account?.defaultCurrency
        set(value) = change { it.copy(defaultCurrency = value) }

    override var ignoredInviteIds: Set<String>
        get() = account?.let { AccountRegistry.decodeIds(it.ignoredInviteIdsJson) }.orEmpty()
        set(value) = change { it.copy(ignoredInviteIdsJson = AccountRegistry.encodeIds(value)) }

    override var allowSelfSignedCerts: Boolean
        get() = account?.allowSelfSignedCerts ?: false
        set(value) = change { it.copy(allowSelfSignedCerts = value) }

    override var lastOpenedListId: String?
        get() = lastOpened.lastOpenedListId
        set(value) {
            lastOpened.lastOpenedListId = value
        }

    private fun change(mutate: (AccountEntity) -> AccountEntity) {
        val id = localId ?: return
        registry.updateInBackground(id, mutate)
    }
}

/** The current account's API client, for the screens that talk to one server. */
class CurrentAccountApi(
    private val currentAccount: CurrentAccount,
    private val sessions: AccountSessions,
) : ApiSource {
    override suspend fun get(): Api {
        val id = currentAccount.localId ?: error("No account is signed in")
        return sessions.get(id).api
    }
}
