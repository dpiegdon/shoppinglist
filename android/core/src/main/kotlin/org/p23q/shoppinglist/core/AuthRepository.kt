package org.p23q.shoppinglist.core

import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.account.SecretStore
import org.p23q.shoppinglist.core.account.normalizeServerUrl
import org.p23q.shoppinglist.core.account.serverLabel
import org.p23q.shoppinglist.core.api.ApiException
import org.p23q.shoppinglist.core.api.LoginRequest
import org.p23q.shoppinglist.core.api.MIN_SERVER_PROTOCOL
import org.p23q.shoppinglist.core.api.PROTOCOL_VERSION
import org.p23q.shoppinglist.core.api.RegisterRequest
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.inTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.util.UUID

/**
 * The server is older than [MIN_SERVER_PROTOCOL], or too old to say which protocol it speaks: this
 * build will not sign in there. [serverProtocol] is what it said, null for nothing.
 */
class ServerTooOldException(val serverProtocol: Int?) :
    Exception("server protocol ${serverProtocol ?: "unknown"} is below $MIN_SERVER_PROTOCOL")

/**
 * The server speaks a newer protocol than [PROTOCOL_VERSION]: it would refuse this build's sign-in
 * with `426`. [downloadUrl] is the app package the server offers, null when it carries none.
 */
class AppTooOldException(val serverProtocol: Int, val downloadUrl: String?) :
    Exception("server protocol $serverProtocol is above $PROTOCOL_VERSION")

/**
 * Whatever answered at the address is not a Tuppu server: `/app-version` came back as something
 * other than its JSON (a captive portal, a web host answering every path with a page), or as a 404
 * that names no protocol — which every server since T-297 does, APK or not.
 */
class NotATuppuServerException(cause: Throwable? = null) : Exception("no Tuppu server answered", cause)

/**
 * Coordinates login/register/logout across the API, the accounts and the local mirror. An interface
 * (not just [AuthRepositoryImpl] directly) so the view models that use it can be tested with a
 * fake, no network/DB required.
 */
interface AuthRepository {
    /**
     * Creates an account on the server at [serverUrl]. Asks the server's protocol first, as
     * [login] does, and throws what [login] throws for it.
     */
    suspend fun register(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean = false)

    /**
     * Signs in at [serverUrl] and returns the local id of the account: the existing row for that
     * server and server-side account, signed in again with its lists as they are, or a new row.
     *
     * Before the first sign-in to a server whose protocol this device does not know yet, asks
     * `/app-version` — the first request that server gets — and throws, storing nothing:
     * [NotATuppuServerException] if no Tuppu server answered; [ServerTooOldException] if the
     * server names no protocol or one below [MIN_SERVER_PROTOCOL]; [AppTooOldException] if it
     * names one above [PROTOCOL_VERSION].
     *
     * Unless [keepOtherAccounts], every other server account is removed once the server has
     * accepted the credentials and before this one is stored: whoever else this device held lists
     * for is not the person who just signed in, and their mirror goes (T-260). Doing it here, in
     * one step with the sign-in, leaves no moment in which the new account sits signed in beside
     * the previous person's lists, whatever fails afterwards. Local accounts are never removed.
     *
     * The account's default currency is read afterwards, best-effort: a failure there does not
     * undo a sign-in the server has already accepted.
     */
    suspend fun login(
        serverUrl: String,
        email: String,
        password: String,
        allowSelfSignedCerts: Boolean = false,
        keepOtherAccounts: Boolean = false,
    ): String

    /**
     * Best-effort server-side token revoke, then always clears the local session regardless.
     * Unpushed edits survive for [login] to judge, exactly as on a forced logout — see
     * [clearLocalSession]; logging out is not a reason to throw away edits that never went out.
     */
    suspend fun logout(accountId: String)

    /**
     * Signs one account out WITHOUT contacting the server. For a forced logout after the server
     * has already rejected our token (401): the token is dead, so a server call is pointless.
     *
     * Keeps whatever of the account's lists is still unpushed, and drops the rest (T-260), along
     * with the token; the row stays, `signedIn = false`, and says whose rows those are. This runs
     * on ANY 401 carrying a bearer token, which per the Wire Contract includes an idle-expired
     * session and a password change on another device (that one revokes every other session by
     * design): edit the list offline, change the password on the web, foreground the phone, and
     * the unpushed queue must still be there when the same account signs in again.
     *
     * What is dropped is only what the server can send again: the cursor is reset, so the next
     * login re-pulls from 0 regardless, and keeping a synced copy on disk until then would buy
     * nothing and leave a signed-out account holding more than it needs to. Other accounts are
     * not touched.
     */
    suspend fun clearLocalSession(accountId: String)

    /**
     * Removes the account from this device: its lists, their items, its token and its row.
     *
     * Every other account on the same server has its sync cursor reset, so its next sync pulls
     * from 0: a list both accounts can see is one row here, owned by whichever account pulled it
     * first, and removing that account deletes the row the other one's cursor has already moved
     * past (T-298; how to key such lists is T-292's).
     */
    suspend fun removeAccount(accountId: String)

    /** Removes every server account but [keep]; local accounts stay. [login] does this itself. */
    suspend fun removeOtherAccounts(keep: String)

    /**
     * Whether the server at [serverUrl] currently accepts new accounts (T-276), checked up front on
     * the login screen — mirroring the web client, which asks before the user fills in the whole
     * form. Best-effort: a network failure or a server too old to answer must not block someone
     * who can register, so callers should treat a thrown exception the same as `true`.
     */
    suspend fun registrationAllowed(serverUrl: String, allowSelfSignedCerts: Boolean = false): Boolean

    fun lastOpenedListId(): String?
}

/**
 * Built by :app's AuthRepositoryModule, which supplies [deviceName] (the label the server shows in
 * the account's session list) from the platform.
 */
class AuthRepositoryImpl(
    private val sessions: AccountSessions,
    private val registry: AccountRegistry,
    private val secrets: SecretStore,
    private val lastOpened: LastOpenedListStore,
    private val appDb: AppDb,
    private val deviceName: String,
) : AuthRepository {

    override suspend fun register(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean) {
        val url = normalizeServerUrl(serverUrl)
        checkServerProtocol(url, allowSelfSignedCerts)
        sessions.unbound(url, allowSelfSignedCerts).register(RegisterRequest(email, password))
    }

    override suspend fun login(
        serverUrl: String,
        email: String,
        password: String,
        allowSelfSignedCerts: Boolean,
        keepOtherAccounts: Boolean,
    ): String {
        val url = normalizeServerUrl(serverUrl)
        val protocol = checkServerProtocol(url, allowSelfSignedCerts)
        val response = sessions.unbound(url, allowSelfSignedCerts)
            .login(LoginRequest(email, password, deviceName, PLATFORM))

        // The same server and the same account: the lists this device already holds for it are its
        // own, unpushed edits and all, and the cursor-0 pull that follows reconciles them (T-260).
        registry.load()
        val existing = registry.find(url, response.accountId)
        // Before the new account is stored, not after: see the KDoc.
        if (!keepOtherAccounts) removeOtherServerAccounts(keep = existing?.id)
        val id = existing?.id ?: UUID.randomUUID().toString()
        secrets.setToken(id, response.token)
        if (existing != null) {
            registry.update(id) {
                it.copy(
                    email = response.email,
                    isAdmin = response.isAdmin,
                    signedIn = true,
                    syncCursor = 0,
                    serverProtocol = protocol ?: it.serverProtocol,
                    allowSelfSignedCerts = allowSelfSignedCerts,
                )
            }
        } else {
            registry.add(
                AccountEntity(
                    id = id,
                    serverUrl = url,
                    accountId = response.accountId,
                    email = response.email,
                    isAdmin = response.isAdmin,
                    label = serverLabel(url),
                    signedIn = true,
                    serverProtocol = protocol,
                    allowSelfSignedCerts = allowSelfSignedCerts,
                ),
            )
        }
        val currency = try {
            sessions.get(id).api.getSettings().defaultCurrency
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort; the next Settings visit reads it again.
            return id
        }
        registry.update(id) { it.copy(defaultCurrency = currency) }
        return id
    }

    /**
     * The protocol floor (T-291). Asked whenever this device holds no protocol for the server that
     * this build can work with — so before the first login or registration there, and the request
     * is the first that server gets — and answered from the stored value otherwise. Returns what
     * the server said, or null when it was not asked.
     *
     * The server says it in a 200 (it carries an app package) and in the `no_app_package` 404 (it
     * does not, T-297). A 404 without it is a server from before `/app-version` existed, or no
     * Tuppu server at all; either way nothing this build can sign in to. Any other failure is not
     * an answer and propagates as it is (offline, a bad certificate, a server error), for the
     * login screen to report as it reports every other.
     */
    private suspend fun checkServerProtocol(url: String, allowSelfSignedCerts: Boolean): Int? {
        val known = registry.load().filter { it.serverUrl == url }.mapNotNull { it.serverProtocol }.maxOrNull()
        if (known != null && known in MIN_SERVER_PROTOCOL..PROTOCOL_VERSION) return null
        var downloadUrl: String? = null
        val protocol = try {
            sessions.unbound(url, allowSelfSignedCerts).appVersion().also { downloadUrl = it.downloadUrl }.protocol
        } catch (e: ApiException) {
            if (e.httpStatus != 404) throw e
            e.protocol ?: throw NotATuppuServerException(e)
        } catch (e: SerializationException) {
            // A 200 that is not the endpoint's JSON.
            throw NotATuppuServerException(e)
        }
        if (protocol == null || protocol < MIN_SERVER_PROTOCOL) throw ServerTooOldException(protocol)
        if (protocol > PROTOCOL_VERSION) throw AppTooOldException(protocol, downloadUrl)
        return protocol
    }

    override suspend fun logout(accountId: String) {
        runCatching { sessions.get(accountId).api.logout() }
        clearLocalSession(accountId)
    }

    override suspend fun registrationAllowed(serverUrl: String, allowSelfSignedCerts: Boolean): Boolean =
        sessions.unbound(normalizeServerUrl(serverUrl), allowSelfSignedCerts).registrationStatus().allowRegistration

    override suspend fun clearLocalSession(accountId: String) = registry.withAccountLock(accountId) {
        clearLocalSessionLocked(accountId)
    }

    private suspend fun clearLocalSessionLocked(accountId: String) {
        secrets.setToken(accountId, null)
        registry.update(accountId) { it.copy(signedIn = false, isAdmin = false, syncCursor = 0) } ?: return
        // Unpushed work is the only thing worth keeping across a logout; see the KDoc above. Items
        // first, so a clean list that still holds an unpushed item stays with it.
        appDb.inTransaction {
            appDb.itemDao().deleteSyncedRowsForAccount(accountId)
            appDb.listDao().deleteSyncedRowsForAccount(accountId)
        }
        forgetLastOpenedListOf(accountId)
    }

    override suspend fun removeAccount(accountId: String) {
        // Under the account's lock, so no sync of it is between its request and its merge.
        val serverUrl = registry.withAccountLock(accountId) {
            val serverUrl = registry.load().firstOrNull { it.id == accountId }?.serverUrl
            secrets.setToken(accountId, null)
            registry.remove(accountId)
            sessions.drop(accountId)
            forgetLastOpenedListOf(accountId)
            serverUrl
        }
        // Each under its own lock, taken only once the removed account's is released: a sync
        // running for one of them would otherwise store its new cursor over the reset.
        if (serverUrl != null) {
            registry.snapshot().filter { it.serverUrl == serverUrl }.forEach { other ->
                registry.withAccountLock(other.id) { registry.update(other.id) { it.copy(syncCursor = 0) } }
            }
        }
    }

    override suspend fun removeOtherAccounts(keep: String) = removeOtherServerAccounts(keep)

    private suspend fun removeOtherServerAccounts(keep: String?) {
        registry.load().filter { it.isServer && it.id != keep }.forEach { removeAccount(it.id) }
    }

    /** A cold start must not reopen a list this account no longer shows; another account's is kept. */
    private suspend fun forgetLastOpenedListOf(accountId: String) {
        val listId = lastOpened.lastOpenedListId ?: return
        val owner = appDb.listDao().getById(listId)?.accountId
        if (owner == null || owner == accountId) lastOpened.lastOpenedListId = null
    }

    override fun lastOpenedListId(): String? = lastOpened.lastOpenedListId

    private companion object {
        // Selects the server's long (62-day) inactivity window for this session —
        // appropriate here because the token lives in EncryptedSharedPreferences on a
        // personal device, unlike the web client's shorter window (T-104).
        const val PLATFORM = "android"
    }
}
