package org.p23q.shoppinglist.data

import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.LoginExpectation
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.account.SecretStore
import org.p23q.shoppinglist.core.api.Api
import org.p23q.shoppinglist.core.api.AuthInterceptor
import org.p23q.shoppinglist.core.api.ErrorInterceptor
import org.p23q.shoppinglist.core.api.ProtocolInterceptor
import org.p23q.shoppinglist.core.api.TokenProvider
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.CollaboratorChangeNotifier
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.data.api.RetrofitApiFactory

/** An in-memory [LastServerAddress] that keeps what it is given as [ServerConfig] does: normalised. */
class FakeLastServerAddress(var url: String? = null, var allowSelfSigned: Boolean = false) : LastServerAddress {
    override suspend fun lastServerUrl(): String? = url

    override suspend fun setLastServerUrl(url: String) {
        this.url = org.p23q.shoppinglist.core.account.normalizeServerUrl(url)
    }

    override suspend fun lastAllowSelfSignedCerts(): Boolean = allowSelfSigned

    override suspend fun setLastAllowSelfSignedCerts(allow: Boolean) {
        allowSelfSigned = allow
    }
}

/** The local id the tests' one account has, wherever a test needs an account behind its lists. */
const val TEST_ACCOUNT_ID = "local-account-1"

/** The server URL of a test account that never gets a request. */
const val TEST_SERVER_URL = "https://lists.example.test/"

/** An in-memory [SecretStore] and [LastOpenedListStore]. */
class FakeSecretStore : SecretStore, LastOpenedListStore {
    val tokens = mutableMapOf<String, String>()
    override var lastOpenedListId: String? = null

    override fun token(accountId: String): String? = tokens[accountId]

    override fun setToken(accountId: String, token: String?) {
        if (token == null) tokens.remove(accountId) else tokens[accountId] = token
    }
}

fun testAccount(
    id: String = TEST_ACCOUNT_ID,
    serverUrl: String = TEST_SERVER_URL,
    accountId: String? = "acct-me",
    email: String? = "me@example.com",
    signedIn: Boolean = true,
): AccountEntity = AccountEntity(
    id = id,
    serverUrl = serverUrl,
    accountId = accountId,
    email = email,
    label = "test",
    signedIn = signedIn,
)

/**
 * Inserts an account row straight into the table, for tests that only need their lists to have an
 * owner. A test that also uses an [AccountRegistry] adds its accounts through the registry instead.
 */
suspend fun AppDb.insertTestAccount(account: AccountEntity = testAccount()) = accountDao().insert(account)

/** Marks the items pushed and acknowledged, as a sync whose answer echoed them would. */
suspend fun AppDb.markItemsClean(localIds: List<String>) {
    for (id in localIds) itemDao().get(id)?.let { itemDao().upsert(it.copy(dirty = false)) }
}

/** Every account's items that are waiting to be pushed. */
suspend fun AppDb.itemsToPush(): List<org.p23q.shoppinglist.core.db.ItemEntity> =
    accountDao().all().flatMap { itemDao().dirtyRowsForAccount(it.id) }

/** Every account's lists that are waiting to be pushed. */
suspend fun AppDb.listsToPush(): List<org.p23q.shoppinglist.core.db.ListEntity> =
    accountDao().all().flatMap { listDao().dirtyRowsForAccount(it.id) }

/** A [ListsRepo] over [db], as the screens get one. */
fun testListsRepo(db: AppDb): ListsRepo =
    ListsRepo(db, DeviceIdProvider { "this-device" }, org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

/** A `/sync` answer that brings the list its server calls [serverId], as a pull after joining one does. */
fun syncResponseWithList(serverId: String, name: String = "Shared", cursor: Long = 1): String = """
    {"cursor": $cursor, "changes": {"lists": [{"id": "$serverId", "created_at": 1000, "fields": {
      "name": {"value": "$name", "updated_at": 1000, "updated_by": "other-device"},
      "category_order": {"value": [], "updated_at": 1000, "updated_by": "other-device"},
      "notes": {"value": null, "updated_at": 1000, "updated_by": "other-device"},
      "deleted": {"value": false, "updated_at": 1000, "updated_by": "other-device"}
    }}], "items": []}}
""".trimIndent()

/** An API client against whatever URL [baseUrl] says at the time of each call, like the app's. */
fun testApi(
    json: Json = Json { ignoreUnknownKeys = true },
    token: () -> String? = { null },
    baseUrl: () -> String?,
): suspend () -> Api = {
    RetrofitApiFactory(json).create(
        baseUrl() ?: error("No account is signed in"),
        false,
        listOf(ProtocolInterceptor(), AuthInterceptor(TokenProvider(token)), ErrorInterceptor(json)),
    )
}

/**
 * The account wiring the app builds in Hilt, over a test database: registry, secrets, sessions
 * and the sync status.
 */
class TestAccounts(val db: AppDb, val json: Json = Json { ignoreUnknownKeys = true }) {
    val secrets = FakeSecretStore()
    val registry = AccountRegistry(db)
    val syncStatus = SyncStatus()
    val sessions = AccountSessions(registry, secrets, RetrofitApiFactory(json), json, syncStatus)

    /** Adds a signed-in account on [serverUrl] with [token]. */
    suspend fun add(
        serverUrl: String,
        id: String = TEST_ACCOUNT_ID,
        token: String? = "tok-123",
        accountId: String? = "acct-me",
        email: String? = "me@example.com",
        // Known, as after a sign-in, so a sync does not ask the server for it first (T-304).
        serverProtocol: Int? = org.p23q.shoppinglist.core.api.PROTOCOL_VERSION,
    ): AccountEntity {
        if (token != null) secrets.setToken(id, token)
        return registry.add(testAccount(id, serverUrl, accountId, email, signedIn = token != null).copy(serverProtocol = serverProtocol))
    }

    /** The [ListAccounts] the list screens get, over these accounts. */
    fun listAccounts(listsRepo: ListsRepo = testListsRepo(db)): ListAccounts = ListAccounts(listsRepo, registry, sessions)

    /** The [Syncer] the app provides over [engine]: a joined list is pulled for the account that joined it. */
    fun syncer(engine: SyncEngine = syncEngine()): org.p23q.shoppinglist.core.sync.Syncer = object : org.p23q.shoppinglist.core.sync.Syncer {
        override suspend fun syncNow(fullLists: List<String>) = engine.syncNow(fullLists)

        override suspend fun syncJoined(accountId: String, serverListId: String) =
            engine.syncNow(listOf(serverListId), fullListsAccountId = accountId)
    }

    fun syncEngine(
        deviceId: DeviceIdProvider = DeviceIdProvider { "this-device" },
        notifier: CollaboratorChangeNotifier = CollaboratorChangeNotifier { },
    ): SyncEngine = SyncEngine(db.itemDao(), db.listDao(), registry, sessions, deviceId, db, syncStatus, notifier)
}

/**
 * The server address a view-model test points its [testApi] at, set the way the
 * single-session app's ServerConfig was: normalised to end in '/'.
 */
class TestServerAddress {
    @Volatile var url: String? = null

    fun setServerUrl(url: String) {
        this.url = org.p23q.shoppinglist.core.account.normalizeServerUrl(url)
    }
}

/** An [AuthRepository] that records what it was asked and does nothing else. */
open class RecordingAuthRepository : AuthRepository {
    val removed = mutableListOf<String>()

    override suspend fun register(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean) {}

    override suspend fun login(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean, expect: LoginExpectation): String =
        TEST_ACCOUNT_ID

    override suspend fun removeAccount(accountId: String) {
        removed += accountId
    }

    override suspend fun registrationStatus(serverUrl: String, allowSelfSignedCerts: Boolean) = org.p23q.shoppinglist.core.api.RegistrationStatusResponse(allowRegistration = true)

    override fun lastOpenedListId(): String? = null
}

/**
 * A [ListAccounts] over the accounts [db]'s table holds (as [insertTestAccount] puts them there),
 * every one of them holding [token]: for a screen test that only needs its list's account to reach
 * a server, typically `insertTestAccount(testAccount(serverUrl = server.url("/").toString()))`.
 */
fun testListAccounts(
    db: AppDb,
    listsRepo: ListsRepo = testListsRepo(db),
    token: String? = "tok-123",
    json: Json = Json { ignoreUnknownKeys = true },
): ListAccounts {
    val secrets = object : SecretStore {
        override fun token(accountId: String): String? = token

        override fun setToken(accountId: String, token: String?) {}
    }
    val registry = AccountRegistry(db)
    return ListAccounts(listsRepo, registry, AccountSessions(registry, secrets, RetrofitApiFactory(json), json, SyncStatus()))
}
