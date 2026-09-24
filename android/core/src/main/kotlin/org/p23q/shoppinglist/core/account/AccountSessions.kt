package org.p23q.shoppinglist.core.account

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * A 401 on a request that carried the account's token sets that account's
 * [AccountEntity.signedIn] to false and emits its id on [forcedLogout]; a 426 sets its
 * [AccountEntity.outdated]; a protocol-checked 2xx clears it again. No other account is touched.
 */
class AccountSessions(
    private val registry: AccountRegistry,
    private val secrets: SecretStore,
    private val apiFactory: ApiFactory,
    private val json: Json,
    private val syncStatus: SyncStatus,
) {
    private val cache = ConcurrentHashMap<String, AccountSession>()

    private val _forcedLogout = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The local id of an account whose token the server just rejected. An event, not a state: a
     * late subscriber must not act on a stale one. The account row keeps `signedIn = false`, which
     * is the state; this is only the prompt for the UI to route to the login screen now.
     */
    val forcedLogout: SharedFlow<String> = _forcedLogout.asSharedFlow()

    /**
     * Set by a 426 from a server this device holds no account for yet — a login or registration
     * attempt. Nothing is stored for it, so it lasts for the process, as the per-account flag does
     * across a restart (see [AccountRegistry.load]).
     */
    private val unboundOutdated = MutableStateFlow(false)

    /**
     * Whether the app is too old for every server it has an account on, or for the one it just
     * tried to sign in to — the state in which the UI can do nothing but offer the update (T-240).
     */
    val updateRequired: Flow<Boolean> =
        combine(registry.accounts, unboundOutdated) { accounts, unbound -> updateRequired(accounts, unbound) }
            .distinctUntilChanged()

    /** The current value of [updateRequired]. */
    fun isUpdateRequired(): Boolean = updateRequired(registry.snapshot(), unboundOutdated.value)

    private fun updateRequired(accounts: List<AccountEntity>, unbound: Boolean): Boolean {
        val servers = accounts.filter { it.isServer }
        return unbound || (servers.isNotEmpty() && servers.all { it.outdated })
    }

    /** The session for a server account; throws [IllegalStateException] for any other id. */
    fun get(accountId: String): AccountSession {
        val account = registry.get(accountId) ?: error("No account $accountId")
        val serverUrl = account.serverUrl ?: error("Account $accountId has no server")
        val key = serverUrl to account.allowSelfSignedCerts
        cache[accountId]?.takeIf { it.builtFor == key }?.let { return it }
        val events = object : ApiEvents {
            override fun onUnauthorized() {
                registry.updateInBackground(accountId) { it.copy(signedIn = false) }
                _forcedLogout.tryEmit(accountId)
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
     * An API client for a server with no account behind it: for what is asked before one exists —
     * `/app-version`, `/registration-status`, `/login`, `/register`. It carries no token.
     */
    fun unbound(serverUrl: String, allowSelfSignedCerts: Boolean): Api = apiFactory.create(
        serverUrl,
        allowSelfSignedCerts,
        listOf(
            ProtocolInterceptor(),
            ErrorInterceptor(
                json,
                object : ApiEvents {
                    override fun onOutdated() {
                        unboundOutdated.value = true
                    }
                },
            ),
        ),
    )

    /** Forgets a removed account's session. */
    fun drop(accountId: String) {
        cache.remove(accountId)
        syncStatus.remove(accountId)
    }
}
