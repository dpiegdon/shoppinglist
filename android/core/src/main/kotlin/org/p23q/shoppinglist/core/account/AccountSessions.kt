package org.p23q.shoppinglist.core.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import org.p23q.shoppinglist.core.api.Api
import org.p23q.shoppinglist.core.api.ApiEvents
import org.p23q.shoppinglist.core.api.AuthInterceptor
import org.p23q.shoppinglist.core.api.ErrorInterceptor
import org.p23q.shoppinglist.core.api.ProtocolInterceptor
import org.p23q.shoppinglist.core.api.TokenProvider
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.sync.AccountSyncStatus
import org.p23q.shoppinglist.core.sync.SyncStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds a Retrofit [Api] for one server. Implemented in :app, which owns Retrofit, OkHttp's client
 * configuration and the debug-only certificate opt-in. [interceptors] run in the order given.
 */
fun interface ApiFactory {
    fun create(baseUrl: String, allowSelfSignedCerts: Boolean, interceptors: List<Interceptor>): Api
}

/**
 * Everything that talks to one server account: its API client, bound to that account's token,
 * its protocol header and its error reporting; and its share of [SyncStatus].
 */
class AccountSession internal constructor(
    val accountId: String,
    val api: Api,
    val syncStatus: AccountSyncStatus,
    internal val builtFor: Pair<String, Boolean>,
)

/**
 * The [AccountSession]s, one per server account, built on first use and cached by local account
 * id. A session is rebuilt when its account's server URL or certificate opt-in changes, and dropped
 * when the account is removed.
 *
 * A 401 on a request that carried the account's current token deletes that token and sets the
 * account's [AccountEntity.signedIn] to false: the row is the state, its lists and unpushed rows
 * stay, and the UI offers the sign-in from it. A 426 sets its [AccountEntity.outdated]; a
 * protocol-checked 2xx clears it again. No other account is touched.
 */
class AccountSessions(
    private val registry: AccountRegistry,
    private val secrets: SecretStore,
    private val apiFactory: ApiFactory,
    private val json: Json,
    private val syncStatus: SyncStatus,
) {
    private val cache = ConcurrentHashMap<String, AccountSession>()

    /**
     * Whether the app is too old for every server it has an account on — the state in which the
     * UI can do nothing but offer the update (T-240). A server this device holds no account for
     * does not count: the login screen reports that one itself, and offers its update there
     * (T-298). Nor while the phone holds the local area (T-293): its lists need no server and
     * stay usable, and each outdated account's section on the overview says so instead.
     */
    val updateRequired: Flow<Boolean> = registry.accounts.map(::updateRequired).distinctUntilChanged()

    /** The current value of [updateRequired]. */
    fun isUpdateRequired(): Boolean = updateRequired(registry.snapshot())

    private fun updateRequired(accounts: List<AccountEntity>): Boolean {
        if (accounts.any { !it.isServer }) return false
        val servers = accounts.filter { it.isServer }
        return servers.isNotEmpty() && servers.all { it.outdated }
    }

    /** The session for a server account; throws [IllegalStateException] for any other id. */
    fun get(accountId: String): AccountSession {
        val account = registry.get(accountId) ?: error("No account $accountId")
        val serverUrl = account.serverUrl ?: error("Account $accountId has no server")
        val key = serverUrl to account.allowSelfSignedCerts
        cache[accountId]?.takeIf { it.builtFor == key }?.let { return it }
        val events = object : ApiEvents {
            override fun onUnauthorized(sentToken: String) {
                // Only the account's current token: a 401 to a request sent before the account
                // signed in again is about a token that is already gone (T-298).
                if (sentToken != secrets.token(accountId)) return
                // Dropped here, where every 401 arrives, background sync included: a dead token
                // must not ride on every later request.
                secrets.setToken(accountId, null)
                registry.updateInBackground(accountId) { it.copy(signedIn = false) }
            }

            override fun onOutdated() {
                if (registry.get(accountId)?.outdated == false) {
                    registry.updateInBackground(accountId) { it.copy(outdated = true) }
                }
            }

            override fun onAccepted() {
                if (registry.get(accountId)?.outdated == true) {
                    registry.updateInBackground(accountId) { it.copy(outdated = false) }
                }
            }
        }
        val api = apiFactory.create(
            serverUrl,
            account.allowSelfSignedCerts,
            listOf(
                // First in the chain: the protocol header rides on every request there is (T-240).
                ProtocolInterceptor(),
                AuthInterceptor(TokenProvider { secrets.token(accountId) }),
                ErrorInterceptor(json, events),
            ),
        )
        val session = AccountSession(accountId, api, syncStatus.account(accountId), key)
        cache[accountId] = session
        return session
    }

    /**
     * An API client that carries no token and reports to no account: for what is asked before an
     * account exists — `/app-version`, `/registration-status`, `/login`, `/register` — and for
     * `/app-version` afterwards, which needs none. A 426 here is an ordinary error for the caller.
     */
    fun unbound(serverUrl: String, allowSelfSignedCerts: Boolean): Api = apiFactory.create(
        serverUrl,
        allowSelfSignedCerts,
        listOf(ProtocolInterceptor(), ErrorInterceptor(json)),
    )

    /** Whether the secret store holds a token for the account. */
    fun hasToken(accountId: String): Boolean = secrets.token(accountId) != null

    /** Forgets a removed account's session. */
    fun drop(accountId: String) {
        cache.remove(accountId)
        syncStatus.remove(accountId)
    }
}
