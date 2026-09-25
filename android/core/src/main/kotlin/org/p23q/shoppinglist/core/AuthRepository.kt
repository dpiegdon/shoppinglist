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
 * The account a sign-in is expected to be (T-300), which [AuthRepository.login] checks against the
 * server's answer before it stores anything.
 */
sealed interface LoginExpectation {
    /** Whoever signs in: the start screen, with no server account on the phone yet. */
    data object Anyone : LoginExpectation

    /** An account not already here and signed in: adding one beside the others. */
    data object NewAccount : LoginExpectation

    /** The account whose local id is [localId], signing in again after its server signed it out. */
    data class Account(val localId: String) : LoginExpectation
}

/** An added account is already on this phone and signed in: there is nothing to add. */
class AlreadyAddedException : Exception("that account is already on this phone")

/** A re-sign-in answered with another account's credentials than the one signing in again. */
class WrongAccountException : Exception("the credentials are another account's")

/** The local area still holds lists, which removing it would lose for good (T-302). */
class LocalAreaNotEmptyException : IllegalStateException("the local area still holds lists")

/**
 * Coordinates login, registration and removal across the API, the accounts and the local mirror.
 * An interface (not just [AuthRepositoryImpl] directly) so the view models that use it can be
 * tested with a fake, no network/DB required.
 *
 * There is no sign-out: a server signs an account out (a `401`, handled in
 * [org.p23q.shoppinglist.core.account.AccountSessions]), and the phone keeps its rows until the
 * account is removed.
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
     * Every other account stays as it is.
     *
     * Before the first sign-in to a server whose protocol this device does not know yet, asks
     * `/app-version` — the first request that server gets — and throws, storing nothing:
     * [NotATuppuServerException] if no Tuppu server answered; [ServerTooOldException] if the
     * server names no protocol or one below [MIN_SERVER_PROTOCOL]; [AppTooOldException] if it
     * names one above [PROTOCOL_VERSION].
     *
     * Once the server has accepted the credentials, the answer is checked against [expect], and a
     * mismatch revokes the session the server just opened and throws, storing nothing:
     * [AlreadyAddedException] when [LoginExpectation.NewAccount] matched a row that is signed
     * in; [WrongAccountException] when [LoginExpectation.Account] got another account than that
     * row's, or another server than the row's once its protocol is known. A row that records no
     * server-side account (a 3.1.0 session that never said whose lists it held) takes on the one
     * that signs in again for it; a row whose protocol is not known yet takes the server URL it
     * was signed in with. [AlreadyAddedException] also when either finds the account at that URL
     * already a row of its own.
     *
     * The account's default currency is read afterwards, best-effort: a failure there does not
     * undo a sign-in the server has already accepted, and the Account screen reads it again.
     */
    suspend fun login(
        serverUrl: String,
        email: String,
        password: String,
        allowSelfSignedCerts: Boolean = false,
        expect: LoginExpectation = LoginExpectation.Anyone,
    ): String

    /**
     * Removes the account from this device: its lists, their items, its token and its row. No
     * other account's rows or cursor are touched: a list two accounts share is a row of each.
     * The local area is refused with [LocalAreaNotEmptyException] while it holds a list.
     */
    suspend fun removeAccount(accountId: String)

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
        expect: LoginExpectation,
    ): String {
        val url = normalizeServerUrl(serverUrl)
        val protocol = checkServerProtocol(url, allowSelfSignedCerts)
        val response = sessions.unbound(url, allowSelfSignedCerts)
            .login(LoginRequest(email, password, deviceName, PLATFORM))

        // The same server and the same account: the lists this device already holds for it are its
        // own, unpushed edits and all, and the cursor-0 pull that follows reconciles them (T-260).
        registry.load()
        val matched = registry.find(url, response.accountId)
        // Checked before anything of the matched row changes: a refused sign-in leaves its token,
        // cursor and flags, and the server session they belong to, as they were (T-300).
        val existing = when (expect) {
            LoginExpectation.Anyone -> matched
            LoginExpectation.NewAccount -> {
                if (matched?.signedIn == true) refuse(url, allowSelfSignedCerts, response.token, AlreadyAddedException())
                matched
            }
            is LoginExpectation.Account -> {
                val row = registry.get(expect.localId)
                when {
                    // Gone meanwhile: nothing to sign in again, so as if adding it.
                    row == null -> {
                        if (matched?.signedIn == true) refuse(url, allowSelfSignedCerts, response.token, AlreadyAddedException())
                        matched
                    }
                    // The local area has no server to sign in to: no server account may take
                    // its row over (T-302).
                    !row.isServer -> refuse(url, allowSelfSignedCerts, response.token, WrongAccountException())
                    // A server this phone has heard from is the row's for good; one it has not
                    // (a migrated row, whose URL is 3.1.0's last typed one) is corrected in the
                    // form, and the row takes the URL it was signed in with (T-304).
                    row.serverProtocol != null && row.serverUrl != url ->
                        refuse(url, allowSelfSignedCerts, response.token, WrongAccountException())
                    row.accountId == response.accountId -> {
                        // The account already has a row at that URL, added beside this one.
                        if (matched != null && matched.id != row.id) {
                            refuse(url, allowSelfSignedCerts, response.token, AlreadyAddedException())
                        }
                        row
                    }
                    row.accountId != null -> refuse(url, allowSelfSignedCerts, response.token, WrongAccountException())
                    // A migrated row whose owner was never recorded (T-300): the account that signs
                    // in for it takes it, unless that account is already a row of its own.
                    matched != null -> refuse(url, allowSelfSignedCerts, response.token, AlreadyAddedException())
                    else -> row
                }
            }
        }
        val id = existing?.id ?: UUID.randomUUID().toString()
        secrets.setToken(id, response.token)
        if (existing != null) {
            registry.update(id) {
                it.copy(
                    serverUrl = url,
                    accountId = response.accountId,
                    label = if (it.serverUrl == url) it.label else serverLabel(url),
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
            // Best-effort; the Account screen reads it again with the initials.
            return id
        }
        registry.update(id) { it.copy(defaultCurrency = currency) }
        return id
    }

    /**
     * Ends the session the server just opened for a sign-in that is refused here, best-effort,
     * and throws [reason]. Its token was never stored, so only the unbound client can carry it.
     */
    private suspend fun refuse(url: String, allowSelfSignedCerts: Boolean, token: String, reason: Exception): Nothing {
        try {
            sessions.unbound(url, allowSelfSignedCerts).logout("Bearer $token")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The session then ends on the server's idle timeout.
        }
        throw reason
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

    override suspend fun registrationAllowed(serverUrl: String, allowSelfSignedCerts: Boolean): Boolean =
        sessions.unbound(normalizeServerUrl(serverUrl), allowSelfSignedCerts).registrationStatus().allowRegistration

    override suspend fun removeAccount(accountId: String) {
        // Under the account's lock, so no sync of it is between its request and its merge.
        registry.withAccountLock(accountId) {
            secrets.setToken(accountId, null)
            if (!registry.remove(accountId)) throw LocalAreaNotEmptyException()
            sessions.drop(accountId)
            forgetLastOpenedListOf(accountId)
        }
    }

    /** A cold start must not reopen a list this account no longer shows; another account's is kept. */
    private suspend fun forgetLastOpenedListOf(accountId: String) {
        val listId = lastOpened.lastOpenedListId ?: return
        val owner = appDb.listDao().get(listId)?.accountId
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
