package org.p23q.shoppinglist.core

import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.AccountSessions
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.core.account.SecretStore
import org.p23q.shoppinglist.core.api.HttpEngineFactory
import org.p23q.shoppinglist.core.api.PROTOCOL_VERSION
import org.p23q.shoppinglist.core.api.TestEngines
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.ItemEntity
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.CollaboratorChangeNotifier
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.SyncTrigger

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

/** Counts what it was asked to schedule. */
class FakeSyncTrigger : SyncTrigger {
    var scheduleCount = 0
        private set
    var immediateCount = 0
        private set

    override fun scheduleAfterEdit() {
        scheduleCount++
    }

    override fun scheduleImmediate() {
        immediateCount++
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

/** The phone's local area, the account with no server. */
fun localAccountRow(id: String = "local", label: String = ""): AccountEntity = AccountEntity(
    id = id,
    kind = AccountEntity.KIND_LOCAL,
    serverUrl = null,
    accountId = null,
    email = null,
    label = label,
    signedIn = false,
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
suspend fun AppDb.itemsToPush(): List<ItemEntity> = accountDao().all().flatMap { itemDao().dirtyRowsForAccount(it.id) }

/** A [ListsRepo] over [db], as the screens get one. */
fun testListsRepo(db: AppDb): ListsRepo = ListsRepo(db, DeviceIdProvider { "this-device" }, FakeSyncTrigger())

/**
 * The account wiring the app builds in Koin, over a test database: registry, secrets, sessions
 * and the sync status, the API clients on OkHttp.
 */
class TestAccounts(
    val db: AppDb,
    val json: Json = Json { ignoreUnknownKeys = true },
    engines: HttpEngineFactory = TestEngines(),
) {
    val secrets = FakeSecretStore()
    val registry = AccountRegistry(db)
    val syncStatus = SyncStatus()
    val sessions = AccountSessions(registry, secrets, engines, json, syncStatus)

    /** Adds a signed-in account on [serverUrl] with [token]. */
    suspend fun add(
        serverUrl: String,
        id: String = TEST_ACCOUNT_ID,
        token: String? = "tok-123",
        accountId: String? = "acct-me",
        email: String? = "me@example.com",
        // Known, as after a sign-in, so a sync does not ask the server for it first (T-304).
        serverProtocol: Int? = PROTOCOL_VERSION,
    ): AccountEntity {
        if (token != null) secrets.setToken(id, token)
        return registry.add(testAccount(id, serverUrl, accountId, email, signedIn = token != null).copy(serverProtocol = serverProtocol))
    }

    fun syncEngine(
        deviceId: DeviceIdProvider = DeviceIdProvider { "this-device" },
        notifier: CollaboratorChangeNotifier = CollaboratorChangeNotifier { },
    ): SyncEngine = SyncEngine(db.itemDao(), db.listDao(), registry, sessions, deviceId, db, syncStatus, notifier)
}
