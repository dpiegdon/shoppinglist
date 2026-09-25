package org.p23q.shoppinglist.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.p23q.shoppinglist.core.AuthRepositoryImpl
import org.p23q.shoppinglist.core.db.AccountEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.testListsRepo
import org.p23q.shoppinglist.core.api.SyncRequest
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.ItemDao
import org.p23q.shoppinglist.core.db.ItemEntity
import org.p23q.shoppinglist.core.db.ListEntity
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.db.toLww
import org.p23q.shoppinglist.core.db.toLwwOptional
import org.robolectric.RobolectricTestRunner
import java.io.File
import org.p23q.shoppinglist.core.sync.CollaboratorChange
import org.p23q.shoppinglist.core.sync.CollaboratorChangeNotifier
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.SyncStatus

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

    private class RecordingNotifier : CollaboratorChangeNotifier {
        val calls = mutableListOf<List<CollaboratorChange>>()
        override suspend fun notifyCollaboratorChanges(changes: List<CollaboratorChange>) {
            calls.add(changes)
        }
    }

    /**
     * An [ItemDao] that lets the test run code inside the sync merge's read-modify-write window
     * (T-261) — between the merge reading the local row and writing the merged one back. Nothing
     * else can reach in there, which is the whole point of the transaction under test.
     */
    private class MergeWindowItemDao(
        private val delegate: ItemDao,
        private val insideTheWindow: suspend () -> Unit,
    ) : ItemDao by delegate {
        override suspend fun getByServerId(accountId: String, serverId: String): ItemEntity? {
            val row = delegate.getByServerId(accountId, serverId)
            insideTheWindow()
            return row
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var serverConfig: ServerConfig
    private lateinit var accounts: TestAccounts
    private lateinit var syncStatus: SyncStatus
    private lateinit var syncEngine: SyncEngine
    private val notifier = RecordingNotifier()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

        val tempFile = File.createTempFile("sync_engine_test", ".preferences_pb")
        tempFile.deleteOnExit()
        serverConfig = ServerConfig(PreferenceDataStoreFactory.create { tempFile })

        accounts = TestAccounts(db)
        syncStatus = accounts.syncStatus
        syncEngine = accounts.syncEngine(deviceId = { serverConfig.deviceId() }, notifier = notifier)
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        // A 401 or a 426 writes the account in the background; it lands before the database goes.
        if (::accounts.isInitialized) runBlocking { accounts.registry.flush() }
        if (::db.isInitialized) db.close()
    }

    /**
     * The one account, signed in on [server], and the list the dummy items are on, clean and with
     * clocks older than anything a test sends, so it only gives those items an owner.
     */
    private suspend fun pointAtServer() {
        accounts.add(server.url("/").toString())
        serverConfig.deviceId() // mint one so it's stable across the test
        db.listDao().upsert(dummyList("list-1", "", dirty = false, at = 0L).copy(kind = "shopping".toLww("", 0L)))
    }

    private suspend fun setCursor(cursor: Long) {
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(syncCursor = cursor) }
    }

    private fun cursor(): Long = accounts.registry.get(TEST_ACCOUNT_ID)!!.syncCursor

    private suspend fun setOwnAccount(accountId: String?, email: String? = "me@example.com") {
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(accountId = accountId, email = email) }
    }

    /**
     * The test's rows name each other by server id, as the wire does; each row's local id is
     * `local-` and its server id, so that a mix-up of the two shows (T-299).
     */
    private fun localId(serverId: String) = "local-$serverId"

    /** The row of [accountId] whose server id is [serverId]. */
    private suspend fun item(serverId: String, accountId: String = TEST_ACCOUNT_ID) = db.itemDao().getByServerId(accountId, serverId)

    private suspend fun list(serverId: String, accountId: String = TEST_ACCOUNT_ID) = db.listDao().getByServerId(accountId, serverId)

    private fun dummyItem(
        id: String,
        name: String,
        dirty: Boolean,
        at: Long = 1_000L,
        list: String = "list-1",
        accountId: String = TEST_ACCOUNT_ID,
    ): ItemEntity = ItemEntity(
        localId = localId(id),
        serverId = id,
        accountId = accountId,
        listLocalId = localId(list),
        createdAt = at,
        name = name.toLww("this-device", at),
        category = null.toLwwOptional("this-device", at),
        stores = "[]".toLww("this-device", at),
        quantity = null.toLwwOptional("this-device", at),
        price = null.toLwwOptional("this-device", at),
        note = null.toLwwOptional("this-device", at),
        status = "todo".toLww("this-device", at),
        deleted = false.toLww("this-device", at),
        dirty = dirty,
    )

    private fun dummyList(id: String, name: String, dirty: Boolean, at: Long = 1_000L, accountId: String = TEST_ACCOUNT_ID): ListEntity = ListEntity(
        localId = localId(id),
        serverId = id,
        accountId = accountId,
        createdAt = at,
        name = name.toLww("this-device", at),
        categoryOrder = "[]".toLww("this-device", at),
        notes = null.toLwwOptional("this-device", at),
        kind = "expenses".toLww("this-device", at),
        currency = "EUR".toLwwOptional("this-device", at),
        deleted = false.toLww("this-device", at),
        dirty = dirty,
    )

    /** An expenses list as the SERVER holds it, vote state included (T-198). */
    private fun expensesListJson(id: String, name: String, at: Long, closeVotes: String): String = """
        {"id": "$id", "created_at": 1000, "fields": {
          "name": {"value": "$name", "updated_at": $at, "updated_by": "server-device"},
          "category_order": {"value": [], "updated_at": $at, "updated_by": "server-device"},
          "notes": {"value": null, "updated_at": $at, "updated_by": "server-device"},
          "kind": {"value": "expenses", "updated_at": $at, "updated_by": "server-device"},
          "currency": {"value": "EUR", "updated_at": $at, "updated_by": "server-device"},
          "deleted": {"value": false, "updated_at": $at, "updated_by": "server-device"}
        }, "members": [], "close_votes": $closeVotes, "closed_at": null}
    """.trimIndent()

    @Test
    fun `push-only sync clears dirty when the server echoes the same clock back`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true, at = 1_000L))

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"cursor": 1, "changes": {"lists": [], "items": [
                  {"id": "item-1", "list_id": "list-1", "created_at": 1000, "fields": {
                    "name": {"value": "Milk", "updated_at": 1000, "updated_by": "this-device"},
                    "category": {"value": null, "updated_at": 1000, "updated_by": "this-device"},
                    "stores": {"value": [], "updated_at": 1000, "updated_by": "this-device"},
                    "quantity": {"value": null, "updated_at": 1000, "updated_by": "this-device"},
                    "price": {"value": null, "updated_at": 1000, "updated_by": "this-device"},
                    "note": {"value": null, "updated_at": 1000, "updated_by": "this-device"},
                    "status": {"value": "todo", "updated_at": 1000, "updated_by": "this-device"},
                    "deleted": {"value": false, "updated_at": 1000, "updated_by": "this-device"}
                  }}
                ]}}
                """.trimIndent(),
            ),
        )

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        val recorded = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(listOf("item-1"), recorded.changes.items.map { it.id })
        val stored = item("item-1")!!
        assertFalse(stored.dirty)
        assertEquals(1L, cursor())

        // A successful run records health for the UI (T-47): last-sync time set, no error, and the
        // pending count recomputed to 0 now that the row synced.
        val health = syncStatus.state.value
        assertFalse(health.inProgress)
        assertNotNull(health.lastSyncAt)
        assertNull(health.lastError)
        assertEquals(0, health.pendingCount)
    }

    @Test
    fun `pull-only sync applies a brand new row from another device`() = runTest {
        pointAtServer()

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"cursor": 5, "changes": {"lists": [], "items": [
                  {"id": "item-2", "list_id": "list-1", "created_at": 2000, "fields": {
                    "name": {"value": "Bread", "updated_at": 2000, "updated_by": "other-device"},
                    "category": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
                    "stores": {"value": [], "updated_at": 2000, "updated_by": "other-device"},
                    "quantity": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
                    "price": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
                    "note": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
                    "status": {"value": "todo", "updated_at": 2000, "updated_by": "other-device"},
                    "deleted": {"value": false, "updated_at": 2000, "updated_by": "other-device"}
                  }}
                ]}}
                """.trimIndent(),
            ),
        )

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        val recorded = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertTrue(recorded.changes.items.isEmpty())
        val stored = item("item-2")!!
        assertEquals("Bread", stored.name.value)
        assertFalse(stored.dirty)
        assertEquals(5L, cursor())
    }

    @Test
    fun `dirty is preserved when the local row was edited again after the request snapshot`() = runTest {
        pointAtServer()
        // Local has a newer name edit (at=5000) than what the server response reflects (at=1000,
        // simulating that the server's reply was computed from an earlier, now-stale snapshot).
        db.itemDao().upsert(dummyItem("item-1", "Milk 2%", dirty = true, at = 5_000L))

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"cursor": 1, "changes": {"lists": [], "items": [
                  {"id": "item-1", "list_id": "list-1", "created_at": 1000, "fields": {
                    "name": {"value": "Milk", "updated_at": 1000, "updated_by": "this-device"},
                    "category": {"value": null, "updated_at": 1000, "updated_by": "this-device"},
                    "stores": {"value": [], "updated_at": 1000, "updated_by": "this-device"},
                    "quantity": {"value": null, "updated_at": 1000, "updated_by": "this-device"},
                    "price": {"value": null, "updated_at": 1000, "updated_by": "this-device"},
                    "note": {"value": null, "updated_at": 1000, "updated_by": "this-device"},
                    "status": {"value": "todo", "updated_at": 1000, "updated_by": "this-device"},
                    "deleted": {"value": false, "updated_at": 1000, "updated_by": "this-device"}
                  }}
                ]}}
                """.trimIndent(),
            ),
        )

        syncEngine.syncNow()

        val stored = item("item-1")!!
        assertEquals("Milk 2%", stored.name.value)
        assertTrue("row should still be dirty since the name field wasn't actually acknowledged", stored.dirty)
    }

    @Test
    fun `410 full_resync_required re-bases the mirror and resyncs at cursor 0`() = runTest {
        pointAtServer()
        // A row the server can reproduce, and one it cannot: only the first is dropped (T-259).
        db.itemDao().upsert(dummyItem("item-synced", "Bread", dirty = false))
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))
        setCursor(999L)

        server.enqueue(
            MockResponse().setResponseCode(410)
                .setBody("""{"error": "full_resync_required", "message": "cursor too old"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        assertNull("a synced row is dropped for the cursor-0 pull to bring back", item("item-synced"))
        assertNotNull("an unpushed edit is not", item("item-1"))
        assertEquals(1L, cursor())
        val firstRequest = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        val retryRequest = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(999L, firstRequest.cursor)
        assertEquals(0L, retryRequest.cursor)
    }

    /**
     * T-259. The old justification for wiping — "the server applied our pushed changes before it
     * rejected the cursor, so nothing pushed is lost" — held only for the rows in THAT request. A
     * push carries at most 250, so a week offline is several batches, and the first of them is the
     * one that gets the 410. Everything past the cap used to be deleted without a word.
     */
    @Test
    fun `410 full_resync_required keeps a backlog larger than one push (T-259)`() = runTest {
        pointAtServer()
        val total = SyncEngine.MAX_CHANGES_PER_SYNC + 50
        repeat(total) { i -> db.itemDao().upsert(dummyItem("item-$i", "Item $i", dirty = true)) }
        setCursor(999L)

        server.enqueue(
            MockResponse().setResponseCode(410)
                .setBody("""{"error": "full_resync_required", "message": "cursor too old"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        assertEquals("every unpushed edit survives the re-base", total, db.itemDao().dirtyRows().size)
        // And the retry carries the backlog rather than being a pure pull — one batch of it here,
        // the rest over the following passes.
        server.takeRequest()
        val retryRequest = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(0L, retryRequest.cursor)
        assertEquals(SyncEngine.MAX_CHANGES_PER_SYNC, retryRequest.changes.items.size)
    }

    /**
     * T-259. A quarantined row is worse off than a merely-dirty one: dirtyRows() excludes it
     * entirely, so no amount of draining the queue first would have saved it, and it is exactly a
     * row the server does not have — it refused it. The user is still meant to correct it.
     */
    @Test
    fun `410 full_resync_required keeps a quarantined row and its reason (T-259)`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("parked", "Dinner", dirty = true))
        db.itemDao().blockRow(localId("parked"), "participant_frozen", "acct-other")
        setCursor(999L)

        server.enqueue(
            MockResponse().setResponseCode(410)
                .setBody("""{"error": "full_resync_required", "message": "cursor too old"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        val parked = item("parked")
        assertNotNull("a refused row the user has not corrected yet is not the server's to reproduce", parked)
        assertTrue(parked!!.syncBlocked)
        assertEquals("participant_frozen", parked.syncBlockedCode)
        assertEquals("acct-other", parked.syncBlockedAccountId)
        assertEquals(1, syncStatus.state.value.blockedCount)
    }

    @Test
    fun `a validation 422 quarantines the named row and the retry pushes the rest`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("bad-item", "Milk", dirty = true))
        db.itemDao().upsert(dummyItem("good-item", "Bread", dirty = true))

        // First push: the server rejects one row, naming it via row_id (T-32 server change).
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"error": "invalid_price", "message": "bad price", "row_id": "bad-item", "field": "price"}""",
            ),
        )
        // Retry push (bad row now quarantined and excluded): accepted.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        assertTrue("the rejected row is quarantined", item("bad-item")!!.syncBlocked)
        assertFalse("the healthy row is not", item("good-item")!!.syncBlocked)

        val first = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        val retry = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(setOf("bad-item", "good-item"), first.changes.items.map { it.id }.toSet())
        assertEquals(setOf("good-item"), retry.changes.items.map { it.id }.toSet())

        // The health surface counts the quarantined row so the UI can flag "needs attention" (T-47).
        assertEquals(1, syncStatus.state.value.blockedCount)
    }

    @Test
    fun `a 422 participant_frozen parks the expense with the reason on it (T-200)`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("dinner", "Dinner", dirty = true))

        // Someone voted to close while this edit was queued offline — the race the form cannot
        // pre-empt, and the one where an explanation is owed.
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"error": "participant_frozen", "message": "frozen", "row_id": "dinner", """ +
                    """"field": "expense", "account_id": "acct-other"}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        val parked = item("dinner")!!
        assertTrue(parked.syncBlocked)
        // Not just that it was refused: the code and the participant it named, so the row itself
        // can say why long after the exception is gone.
        assertEquals("participant_frozen", parked.syncBlockedCode)
        assertEquals("acct-other", parked.syncBlockedAccountId)
    }

    @Test
    fun `a 422 voted_to_close naming a list row parks it instead of wedging the queue (T-198)`() = runTest {
        pointAtServer()
        // A list edit queued before its author voted to close the list, plus an unrelated item edit.
        db.listDao().upsert(dummyList("list-1", "Trip (renamed)", dirty = true, at = 2_000L))
        db.itemDao().upsert(dummyItem("good-item", "Bread", dirty = true))

        // The vote landed first, so the queued rename is refused with the list's own id.
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"error": "voted_to_close", "message": "you agreed to close", "row_id": "list-1"}""",
            ),
        )
        // Retry (the list now parked): accepted, and the server's copy of the list comes back with it.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"cursor": 1, "changes": {"lists": [${expensesListJson("list-1", "Trip", at = 1_000L, closeVotes = "[\"me\"]")}], "items": []}}""",
            ),
        )
        // And a later sync still goes through rather than failing on the same row forever.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 2, "changes": {"lists": [], "items": []}}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        val parked = list("list-1")!!
        assertTrue("the refused list row is quarantined", parked.syncBlocked)
        // Everything the server owns is still mirrored onto the parked row — the vote that caused
        // the refusal included, which is what tells the UI why nothing is moving.
        assertEquals("""["me"]""", parked.closeVotesJson)

        val first = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        val retry = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(listOf("list-1"), first.changes.lists.map { it.id })
        assertTrue("the parked list is not pushed again", retry.changes.lists.isEmpty())
        assertEquals(listOf("good-item"), retry.changes.items.map { it.id })

        assertTrue(syncEngine.syncNow() is SyncResult.Success)
        assertEquals(1, syncStatus.state.value.blockedCount)
    }

    @Test
    fun `a bad field value on a list row is quarantined like an item's (T-198)`() = runTest {
        pointAtServer()
        db.listDao().upsert(dummyList("bad-list", "Trip", dirty = true))
        db.listDao().upsert(dummyList("good-list", "Groceries", dirty = true))

        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"error": "invalid_currency", "message": "bad currency", "row_id": "bad-list", "field": "currency"}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        assertTrue("the rejected list is quarantined", list("bad-list")!!.syncBlocked)
        assertFalse("the healthy one is not", list("good-list")!!.syncBlocked)

        val first = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        val retry = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(setOf("bad-list", "good-list"), first.changes.lists.map { it.id }.toSet())
        assertEquals(setOf("good-list"), retry.changes.lists.map { it.id }.toSet())

        // A blocked list counts towards "needs attention" alongside blocked items (T-47).
        assertEquals(1, syncStatus.state.value.blockedCount)
    }

    @Test
    fun `a failed sync records the error on the health surface (T-47)`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": "server_error", "message": "boom"}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Failed)
        val health = syncStatus.state.value
        assertFalse(health.inProgress)
        assertNotNull(health.lastError)
    }

    @Test
    fun `seedStatus reports the database's counts with no network call, and no verdict (T-265)`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))
        db.itemDao().upsert(dummyItem("item-2", "Bread", dirty = true))
        db.itemDao().upsert(dummyItem("parked", "Dinner", dirty = false))
        db.itemDao().blockRow(localId("parked"), "participant_frozen", "acct-other")
        db.listDao().upsert(dummyList("list-2", "Trip", dirty = true))

        syncEngine.seedStatus()

        // Every scheduled sync trigger requires connectivity; this must not need it, or a cold
        // start offline would still show nothing. No request was ever enqueued on `server`, so a
        // real attempt would throw trying to connect it — reaching the assertions proves that.
        val health = syncStatus.state.value
        assertEquals(3, health.pendingCount)
        assertEquals(1, health.blockedCount)
        // Not a sync attempt: no verdict, no spinner.
        assertFalse(health.inProgress)
        assertNull(health.lastError)
    }

    @Test
    fun `401 surfaces as Unauthorized without touching local state`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "invalid_token", "message": "expired"}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Unauthorized)
        assertTrue("dirty row should be untouched", item("item-1")!!.dirty)
    }

    // --- Too old for this server (T-244) -----------------------------------------------------

    @Test
    fun `426 stops the sync without quarantining, dropping or wiping anything`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))
        db.listDao().upsert(dummyList("list-1", "Groceries", dirty = true))
        server.enqueue(
            MockResponse().setResponseCode(426)
                .setBody("""{"error": "client_outdated", "message": "too old", "protocol": 3}"""),
        )

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.UpdateRequired)
        // The app being outdated says nothing about the queue: every row is still there, still
        // dirty, still unblocked, ready to go out unchanged from an updated build.
        val item = item("item-1")!!
        assertTrue("dirty item should be untouched", item.dirty)
        assertFalse("nothing was quarantined", item.syncBlocked)
        val list = list("list-1")!!
        assertTrue("dirty list should be untouched", list.dirty)
        assertFalse("nothing was quarantined", list.syncBlocked)
        assertEquals(0, syncStatus.state.value.blockedCount)
        // Not a loud failure either: the blocking update screen is what the user is looking at.
        assertNull(syncStatus.state.value.lastError)
        assertFalse(syncStatus.state.value.inProgress)
    }

    @Test
    fun `once the state is set no further request goes out at all`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))
        server.enqueue(
            MockResponse().setResponseCode(426)
                .setBody("""{"error": "client_outdated", "message": "too old", "protocol": 3}"""),
        )

        assertTrue(syncEngine.syncNow() is SyncResult.UpdateRequired)
        val afterFirst = server.requestCount

        // The point of the guard: a scheduled worker and every screen's refresh would otherwise
        // keep hammering a server that will refuse them until the app is updated.
        assertTrue(syncEngine.syncNow() is SyncResult.UpdateRequired)
        assertTrue(syncEngine.syncNow(fullLists = listOf("list-1")) is SyncResult.UpdateRequired)

        assertEquals(afterFirst, server.requestCount)
        assertTrue("dirty row should still be pushable", item("item-1")!!.dirty)
    }

    // --- Collaborator-change detection (T-65) -----------------------------------------------

    /** Wire-shaped item JSON, with the account-scoped top-level last_touched_by (T-64). */
    private fun itemJson(id: String, listId: String, name: String, lastTouchedBy: String?): String {
        val touchedBy = lastTouchedBy?.let { "\"$it\"" } ?: "null"
        return """
            {"id": "$id", "list_id": "$listId", "created_at": 2000, "last_touched_by": $touchedBy, "fields": {
              "name": {"value": "$name", "updated_at": 2000, "updated_by": "other-device"},
              "category": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
              "stores": {"value": [], "updated_at": 2000, "updated_by": "other-device"},
              "quantity": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
              "price": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
              "note": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
              "status": {"value": "todo", "updated_at": 2000, "updated_by": "other-device"},
              "deleted": {"value": false, "updated_at": 2000, "updated_by": "other-device"}
            }}
        """.trimIndent()
    }

    private fun listJson(id: String, name: String): String = """
        {"id": "$id", "created_at": 1000, "fields": {
          "name": {"value": "$name", "updated_at": 1000, "updated_by": "other-device"},
          "category_order": {"value": [], "updated_at": 1000, "updated_by": "other-device"},
          "notes": {"value": null, "updated_at": 1000, "updated_by": "other-device"},
          "deleted": {"value": false, "updated_at": 1000, "updated_by": "other-device"}
        }}
    """.trimIndent()

    private fun syncResponseJson(cursor: Long, lists: List<String>, items: List<String>): String =
        """{"cursor": $cursor, "changes": {"lists": [${lists.joinToString(",")}], "items": [${items.joinToString(",")}]}}"""

    @Test
    fun `items pulled with another account's last_touched_by are reported to the notifier, grouped per list (T-65)`() = runTest {
        pointAtServer()
        setOwnAccount("acc-me")
        setCursor(5)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(
                    cursor = 6,
                    lists = listOf(listJson(id = "list-1", name = "Groceries")),
                    items = listOf(
                        itemJson(id = "i1", listId = "list-1", name = "Milk", lastTouchedBy = "acc-other"),
                        itemJson(id = "i2", listId = "list-1", name = "Eggs", lastTouchedBy = "acc-other"),
                        itemJson(id = "i3", listId = "list-1", name = "Bread", lastTouchedBy = "acc-me"),
                    ),
                ),
            ),
        )

        syncEngine.syncNow()

        assertEquals(1, notifier.calls.size)
        assertEquals(listOf(CollaboratorChange(TEST_ACCOUNT_ID, localId("list-1"), "Groceries", 2)), notifier.calls.single())
    }

    @Test
    fun `an edit by another account on this phone and the same server is not reported (T-304)`() = runTest {
        pointAtServer()
        setOwnAccount("acc-me")
        setCursor(5)
        // Signed out, so only the first account syncs; both still name their server-side account.
        accounts.add(server.url("/").toString(), id = "mate", token = null, accountId = "acc-mate")
        // The same server-side id on another server is someone else there.
        accounts.add("https://elsewhere.example.test/", id = "far", token = null, accountId = "acc-far")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(
                    cursor = 6,
                    lists = listOf(listJson(id = "list-1", name = "Groceries")),
                    items = listOf(
                        itemJson(id = "i1", listId = "list-1", name = "Milk", lastTouchedBy = "acc-mate"),
                        itemJson(id = "i2", listId = "list-1", name = "Eggs", lastTouchedBy = "acc-far"),
                    ),
                ),
            ),
        )

        syncEngine.syncNow()

        assertEquals(listOf(CollaboratorChange(TEST_ACCOUNT_ID, localId("list-1"), "Groceries", 1)), notifier.calls.single())
    }

    @Test
    fun `a pull containing only own-account and null-account rows stays silent (T-65)`() = runTest {
        pointAtServer()
        setOwnAccount("acc-me")
        setCursor(5)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(
                    cursor = 6,
                    lists = listOf(listJson(id = "list-1", name = "Groceries")),
                    items = listOf(
                        itemJson(id = "i1", listId = "list-1", name = "Milk", lastTouchedBy = "acc-me"),
                        itemJson(id = "i2", listId = "list-1", name = "Eggs", lastTouchedBy = null),
                    ),
                ),
            ),
        )

        syncEngine.syncNow()

        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun `a cursor-zero pull (initial hydration or full resync) never notifies (T-65)`() = runTest {
        pointAtServer()
        setOwnAccount("acc-me")
        setCursor(0)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(
                    cursor = 6,
                    lists = listOf(listJson(id = "list-1", name = "Groceries")),
                    items = listOf(itemJson(id = "i1", listId = "list-1", name = "Milk", lastTouchedBy = "acc-other")),
                ),
            ),
        )

        syncEngine.syncNow()

        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun `an unknown own account id stays silent rather than guessing (T-65)`() = runTest {
        pointAtServer()
        setOwnAccount(null)
        setCursor(5)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(
                    cursor = 6,
                    lists = listOf(listJson(id = "list-1", name = "Groceries")),
                    items = listOf(itemJson(id = "i1", listId = "list-1", name = "Milk", lastTouchedBy = "acc-other")),
                ),
            ),
        )

        syncEngine.syncNow()

        assertTrue(notifier.calls.isEmpty())
    }

    @Test
    fun `a null accountId is backfilled from the members roster by email, enabling detection (T-74)`() = runTest {
        pointAtServer()
        setOwnAccount(null, email = "me@example.com")
        setCursor(5)
        // The sync response (applied before the backfill) creates list-1, which the backfill then
        // uses for its members lookup.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(
                    cursor = 6,
                    lists = listOf(listJson(id = "list-1", name = "Groceries")),
                    items = listOf(itemJson(id = "i1", listId = "list-1", name = "Milk", lastTouchedBy = "acc-other")),
                ),
            ),
        )
        // The members roster carries the current user (matched by email) plus the collaborator.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"members": [{"account_id": "acc-me", "email": "me@example.com", "initials": "ME", "joined_at": 1},""" +
                    """{"account_id": "acc-other", "email": "friend@example.com", "initials": "FR", "joined_at": 2}], "invites": []}""",
            ),
        )

        syncEngine.syncNow()

        assertEquals("acc-me", accounts.registry.get(TEST_ACCOUNT_ID)!!.accountId)
        assertEquals(listOf(CollaboratorChange(TEST_ACCOUNT_ID, localId("list-1"), "Groceries", 1)), notifier.calls.single())
    }

    // ---- batch chunking (T-114) ---------------------------------------------

    /** Echo of a row created by [dummyItem], so the merge clears its dirty flag. */
    private fun echoDirtyItemJson(id: String, name: String, at: Long = 1_000L): String = """
        {"id": "$id", "list_id": "list-1", "created_at": $at, "fields": {
          "name": {"value": "$name", "updated_at": $at, "updated_by": "this-device"},
          "category": {"value": null, "updated_at": $at, "updated_by": "this-device"},
          "stores": {"value": [], "updated_at": $at, "updated_by": "this-device"},
          "quantity": {"value": null, "updated_at": $at, "updated_by": "this-device"},
          "price": {"value": null, "updated_at": $at, "updated_by": "this-device"},
          "note": {"value": null, "updated_at": $at, "updated_by": "this-device"},
          "status": {"value": "todo", "updated_at": $at, "updated_by": "this-device"},
          "deleted": {"value": false, "updated_at": $at, "updated_by": "this-device"}
        }}
    """.trimIndent()

    @Test
    fun `a backlog larger than the server cap is pushed across several requests, none over the cap`() = runTest {
        pointAtServer()
        val cap = SyncEngine.MAX_CHANGES_PER_SYNC
        val total = cap + 3
        val ids = (0 until total).map { "item-$it" }
        ids.forEach { db.itemDao().upsert(dummyItem(it, "Name $it", dirty = true)) }

        // Each response echoes exactly the rows that pass sent, so their dirty flags clear and the
        // backlog shrinks — which is what licenses the next pass.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(1, emptyList(), ids.take(cap).map { echoDirtyItemJson(it, "Name ${it.removePrefix("item-")}") }),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(2, emptyList(), ids.drop(cap).map { echoDirtyItemJson(it, "Name ${it.removePrefix("item-")}") }),
            ),
        )

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        assertEquals(total, (result as SyncResult.Success).pushedItems)

        val first = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        val second = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(cap, first.changes.items.size + first.changes.lists.size)
        assertEquals(3, second.changes.items.size + second.changes.lists.size)
        // Every row went exactly once, none lost or duplicated.
        assertEquals(ids.toSet(), (first.changes.items + second.changes.items).map { it.id }.toSet())
        assertEquals(total, first.changes.items.size + second.changes.items.size)
        assertEquals(0, db.itemDao().dirtyRows().size)
    }

    @Test
    fun `full_lists is requested once, not repeated on every chunk`() = runTest {
        pointAtServer()
        val cap = SyncEngine.MAX_CHANGES_PER_SYNC
        val ids = (0 until cap + 1).map { "item-$it" }
        ids.forEach { db.itemDao().upsert(dummyItem(it, "Name $it", dirty = true)) }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(1, emptyList(), ids.take(cap).map { echoDirtyItemJson(it, "Name ${it.removePrefix("item-")}") }),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(2, emptyList(), ids.drop(cap).map { echoDirtyItemJson(it, "Name ${it.removePrefix("item-")}") }),
            ),
        )

        syncEngine.syncNow(fullLists = listOf("list-1"))

        val first = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        val second = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(listOf("list-1"), first.fullLists)
        assertEquals(emptyList<String>(), second.fullLists)
    }

    @Test
    fun `a backlog that does not shrink stops after one pass instead of spinning forever`() = runTest {
        // A pushed row is only marked clean when the server echoes it back. If it never does, the
        // backlog never shrinks — and chunking must not turn that into an infinite request loop.
        // Exactly one response is enqueued: a second pass would block on an empty MockWebServer.
        pointAtServer()
        val total = SyncEngine.MAX_CHANGES_PER_SYNC + 5
        (0 until total).forEach { db.itemDao().upsert(dummyItem("item-$it", "Name $it", dirty = true)) }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(syncResponseJson(1, emptyList(), emptyList())),
        )

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        assertEquals(1, server.requestCount)
        assertEquals(total, db.itemDao().dirtyRows().size)
    }

    /**
     * T-261, the ticket's own scenario: a shopper checks an item off at the exact moment a
     * background sync applies that row. The merge reads the local row, folds the remote clocks into
     * it and writes the WHOLE row back — so an edit landing in between is overwritten with the
     * pre-tap row, and since the pre-tap clocks go back with it the row isn't even left dirty: the
     * tap is gone with nothing queued to recover it. The list screen syncs every 5 s while it is
     * open, so this window is hit in ordinary use.
     *
     * runBlocking, not runTest: this is about two coroutines on real threads, and runTest's virtual
     * clock would skip the wait below without ever letting the other one run.
     */
    @Test
    fun `a tap landing inside the merge's window survives it (T-261)`() = runBlocking<Unit> {
        pointAtServer()
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = false, at = 1_000L))
        setCursor(5L)

        val itemsRepo = ItemsRepo(db, DeviceIdProvider { "this-device" }, FakeSyncTrigger())
        val shopper = CoroutineScope(Dispatchers.IO)
        var tap: Job? = null
        var committedInsideTheWindow = true

        val probedEngine = SyncEngine(
            MergeWindowItemDao(db.itemDao()) {
                if (tap == null) {
                    tap = shopper.launch { itemsRepo.setStatus(localId("item-1"), Status.CHECKED) }
                    committedInsideTheWindow = withTimeoutOrNull(2_000) { tap!!.join() } != null
                }
            },
            db.listDao(), accounts.registry, accounts.sessions, DeviceIdProvider { serverConfig.deviceId() }, db, syncStatus, notifier,
        )

        // The server's copy of the row is newer than the local one, so the merge takes every field
        // from it — including status "todo", which is exactly what would clobber the tap.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"cursor": 6, "changes": {"lists": [], "items": [
                  {"id": "item-1", "list_id": "list-1", "created_at": 1000, "fields": {
                    "name": {"value": "Milk", "updated_at": 2000, "updated_by": "other-device"},
                    "category": {"value": "Dairy", "updated_at": 2000, "updated_by": "other-device"},
                    "stores": {"value": [], "updated_at": 2000, "updated_by": "other-device"},
                    "quantity": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
                    "price": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
                    "note": {"value": null, "updated_at": 2000, "updated_by": "other-device"},
                    "status": {"value": "todo", "updated_at": 2000, "updated_by": "other-device"},
                    "deleted": {"value": false, "updated_at": 2000, "updated_by": "other-device"}
                  }}
                ]}}
                """.trimIndent(),
            ),
        )

        val result = probedEngine.syncNow()
        tap!!.join()

        assertTrue(result is SyncResult.Success)
        val stored = item("item-1")!!
        assertEquals("the tap was overwritten by the merge writing back the pre-tap row", "checked", stored.status.value)
        assertTrue("and, overwritten with the pre-tap clocks, it wasn't even left queued", stored.dirty)
        // The rest of the pull still landed — the tap only claims the field it touched.
        assertEquals("Dairy", stored.category.value)
        assertFalse(
            "an edit committed between the merge's read and its write",
            committedInsideTheWindow,
        )
        shopper.cancel()
    }

    // ---- several accounts (T-291) ----------------------------------------------------

    private val emptyPull = """{"cursor": 7, "changes": {"lists": [], "items": []}}"""

    /** A second signed-in account on its own server, with its own list and a dirty item on it. */
    private suspend fun secondAccount(other: MockWebServer) {
        accounts.add(other.url("/").toString(), id = "second", token = "tok-second", accountId = "acc-second")
        db.listDao().upsert(dummyList("list-2", "Theirs", dirty = false, at = 0L, accountId = "second"))
        db.itemDao().upsert(dummyItem("their-item", "Tea", dirty = true, list = "list-2", accountId = "second"))
    }

    private fun withSecondServer(block: suspend (MockWebServer) -> Unit) = runTest {
        val other = MockWebServer().apply { start() }
        try {
            block(other)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `each account's rows go to its own server, with its own token and cursor`() = withSecondServer { other ->
        pointAtServer()
        secondAccount(other)
        db.itemDao().upsert(dummyItem("my-item", "Milk", dirty = true))
        setCursor(3)
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        other.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 40, "changes": {"lists": [], "items": []}}"""))

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        val mine = server.takeRequest()
        val theirs = other.takeRequest()
        assertEquals("Bearer tok-123", mine.getHeader("Authorization"))
        assertEquals("Bearer tok-second", theirs.getHeader("Authorization"))
        val mineBody = Json.decodeFromString<SyncRequest>(mine.body.readUtf8())
        val theirsBody = Json.decodeFromString<SyncRequest>(theirs.body.readUtf8())
        assertEquals(listOf("my-item"), mineBody.changes.items.map { it.id })
        assertEquals(listOf("their-item"), theirsBody.changes.items.map { it.id })
        assertEquals(3L, mineBody.cursor)
        assertEquals(0L, theirsBody.cursor)
        assertEquals(7L, cursor())
        assertEquals(40L, accounts.registry.get("second")!!.syncCursor)
    }

    @Test
    fun `one account failing does not stop the next, and the result is the worst of them`() = withSecondServer { other ->
        pointAtServer()
        secondAccount(other)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": "server_error", "message": "boom"}"""))
        other.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Failed)
        assertEquals("the second account still synced", 1, other.requestCount)
        assertEquals(7L, accounts.registry.get("second")!!.syncCursor)
        // Per account: the failure is the first's, the success the second's.
        assertNotNull(syncStatus.accounts.value.getValue(TEST_ACCOUNT_ID).lastError)
        assertNull(syncStatus.accounts.value.getValue("second").lastError)
        assertNotNull(syncStatus.accounts.value.getValue("second").lastSyncAt)
        // And the aggregate the status bar shows is the worst of them.
        assertNotNull(syncStatus.state.value.lastError)
        assertNull("one account has never synced", syncStatus.state.value.lastSyncAt)
    }

    @Test
    fun `an unreachable server fails only its own account`() = withSecondServer { other ->
        pointAtServer()
        secondAccount(other)
        server.shutdown()
        other.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        assertTrue(syncEngine.syncNow() is SyncResult.Failed)
        assertEquals(1, other.requestCount)
    }

    @Test
    fun `a 401 signs out only the account whose token was rejected`() = withSecondServer { other ->
        pointAtServer()
        secondAccount(other)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "invalid_token", "message": "expired"}"""))
        other.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        other.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        assertTrue(syncEngine.syncNow() is SyncResult.Unauthorized)

        assertFalse(accounts.registry.get(TEST_ACCOUNT_ID)!!.signedIn)
        assertTrue(accounts.registry.get("second")!!.signedIn)
        // The next run leaves the signed-out account alone and syncs the other.
        assertTrue(syncEngine.syncNow() is SyncResult.Success)
        assertEquals(1, server.requestCount)
        assertEquals(2, other.requestCount)
        accounts.registry.flush()
        assertFalse("written through", db.accountDao().all().first { it.id == TEST_ACCOUNT_ID }.signedIn)
    }

    @Test
    fun `a 426 marks only that account outdated, and the app is not blocked while another works`() = withSecondServer { other ->
        pointAtServer()
        secondAccount(other)
        server.enqueue(MockResponse().setResponseCode(426).setBody("""{"error": "client_outdated", "message": "too old"}"""))
        other.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        other.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        assertTrue(syncEngine.syncNow() is SyncResult.UpdateRequired)

        assertTrue(accounts.registry.get(TEST_ACCOUNT_ID)!!.outdated)
        assertFalse(accounts.registry.get("second")!!.outdated)
        assertFalse("one server still accepts this build", accounts.sessions.isUpdateRequired())
        assertTrue(syncEngine.syncNow() is SyncResult.Success)
        assertEquals("the outdated account is not asked again", 1, server.requestCount)
    }

    @Test
    fun `every account outdated blocks the app, and an accepted request clears it`() = runTest {
        pointAtServer()
        server.enqueue(MockResponse().setResponseCode(426).setBody("""{"error": "client_outdated", "message": "too old"}"""))
        assertTrue(syncEngine.syncNow() is SyncResult.UpdateRequired)
        assertTrue(accounts.sessions.isUpdateRequired())

        // A request the server does check the protocol on, and accepts (a server rolled back, say).
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"lists": []}"""))
        accounts.sessions.get(TEST_ACCOUNT_ID).api.lists()

        assertFalse(accounts.registry.get(TEST_ACCOUNT_ID)!!.outdated)
        assertFalse(accounts.sessions.isUpdateRequired())
    }

    @Test
    fun `a 410 re-base drops only that account's synced rows`() = withSecondServer { other ->
        pointAtServer()
        secondAccount(other)
        db.itemDao().upsert(dummyItem("their-synced", "Coffee", dirty = false, list = "list-2", accountId = "second"))
        db.itemDao().upsert(dummyItem("my-synced", "Bread", dirty = false))
        setCursor(999)
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error": "full_resync_required", "message": "old"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        other.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        assertNull(item("my-synced"))
        assertNotNull("another account's mirror is not re-based", item("their-synced", "second"))
        assertNotNull(list("list-2", "second"))
    }

    @Test
    fun `a full snapshot for a named account is asked of that account alone`() = withSecondServer { other ->
        pointAtServer()
        secondAccount(other)
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        other.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        // list-2 is a server id the second account holds, but server ids are per account (T-299).
        syncEngine.syncNow(fullLists = listOf("list-2", "joined-just-now"), fullListsAccountId = TEST_ACCOUNT_ID)

        val mine = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        val theirs = Json.decodeFromString<SyncRequest>(other.takeRequest().body.readUtf8())
        assertEquals(listOf("list-2", "joined-just-now"), mine.fullLists)
        assertEquals(emptyList<String>(), theirs.fullLists)
    }

    @Test
    fun `a full snapshot for no account in particular is asked of each account that holds the list`() = withSecondServer { other ->
        pointAtServer()
        secondAccount(other)
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        other.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        syncEngine.syncNow(fullLists = listOf("list-2", "list-1", "nobody-has-it"))

        val mine = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        val theirs = Json.decodeFromString<SyncRequest>(other.takeRequest().body.readUtf8())
        assertEquals(listOf("list-1"), mine.fullLists)
        assertEquals(listOf("list-2"), theirs.fullLists)
    }

    @Test
    fun `a list pulled for the first time belongs to the account that pulled it`() = runTest {
        pointAtServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(cursor = 6, lists = listOf(listJson(id = "new-list", name = "Shared")), items = emptyList()),
            ),
        )

        syncEngine.syncNow()

        assertEquals(TEST_ACCOUNT_ID, list("new-list")!!.accountId)
    }

    @Test
    fun `with no signed-in account there is nothing to sync and nothing is sent`() = runTest {
        accounts.add(server.url("/").toString(), token = null)

        assertTrue(syncEngine.syncNow() is SyncResult.Unauthorized)
        assertEquals(0, server.requestCount)
    }

    // ---- a list two accounts share (T-299) ------------------------------------------------

    /** A second account on the same server as the first, as a second login there makes it. */
    private suspend fun mateOnTheSameServer() {
        accounts.add(server.url("/").toString(), id = "mate", token = "tok-mate", accountId = "acc-mate")
    }

    /** Both accounts pull the list "shared" with its item "shared-item": the first, then the mate. */
    private suspend fun pullSharedListIntoBoth() {
        val pull = { cursor: Long ->
            syncResponseJson(
                cursor = cursor,
                lists = listOf(listJson(id = "shared", name = "Trip")),
                items = listOf(itemJson(id = "shared-item", listId = "shared", name = "Tent", lastTouchedBy = null)),
            )
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(pull(6)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(pull(9)))
        assertTrue(syncEngine.syncNow() is SyncResult.Success)
        repeat(2) { server.takeRequest() }
    }

    @Test
    fun `two accounts that share a list pull it into a row each, under their own account`() = runTest {
        pointAtServer()
        mateOnTheSameServer()

        pullSharedListIntoBoth()

        val mine = list("shared")!!
        val theirs = list("shared", "mate")!!
        assertNotEquals("a row each", mine.localId, theirs.localId)
        assertEquals(TEST_ACCOUNT_ID, mine.accountId)
        assertEquals("mate", theirs.accountId)
        // Each row holds the pull, not just a stub of the list: the merge filled the account's own row.
        for (row in listOf(mine, theirs)) {
            assertEquals("Trip", row.name.value)
            assertFalse(row.deleted.value)
        }
        val myItem = item("shared-item")!!
        val theirItem = item("shared-item", "mate")!!
        assertEquals("Tent", theirItem.name.value)
        assertNotEquals(myItem.localId, theirItem.localId)
        assertEquals("each item is on its own account's row of the list", mine.localId, myItem.listLocalId)
        assertEquals(theirs.localId, theirItem.listLocalId)
        assertEquals("an item's account is its list's", TEST_ACCOUNT_ID, myItem.accountId)
        assertEquals("mate", theirItem.accountId)
        assertEquals(6L, cursor())
        assertEquals(9L, accounts.registry.get("mate")!!.syncCursor)
    }

    @Test
    fun `a pull merges into the syncing account's row and leaves the other's alone`() = runTest {
        pointAtServer()
        mateOnTheSameServer()
        pullSharedListIntoBoth()
        val renamed = listJson(id = "shared", name = "Trip 2").replace("\"updated_at\": 1000", "\"updated_at\": 3000")
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncResponseJson(cursor = 12, lists = listOf(renamed), items = emptyList())))

        assertTrue(syncEngine.syncAccount("mate") is SyncResult.Success)

        assertEquals("Trip 2", list("shared", "mate")!!.name.value)
        assertEquals("Trip", list("shared")!!.name.value)
        assertEquals(1000L, list("shared")!!.name.updatedAt)
    }

    @Test
    fun `an edit to one account's row goes out with that account alone, and its refusal parks that row only`() = runTest {
        pointAtServer()
        mateOnTheSameServer()
        pullSharedListIntoBoth()
        val itemsRepo = ItemsRepo(db, DeviceIdProvider { "this-device" }, FakeSyncTrigger())
        itemsRepo.setStatus(item("shared-item")!!.localId, Status.CHECKED)
        assertTrue(item("shared-item")!!.dirty)
        assertFalse("the other account's row is not edited with it", item("shared-item", "mate")!!.dirty)

        // Mine: refused, then the retry without it. The mate's: nothing to push.
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"error": "invalid_status", "message": "no", "row_id": "shared-item", "field": "status"}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        val first = server.takeRequest()
        assertEquals("Bearer tok-123", first.getHeader("Authorization"))
        val pushed = Json.decodeFromString<SyncRequest>(first.body.readUtf8()).changes.items.single()
        assertEquals("the server's ids go out, not this phone's", "shared-item", pushed.id)
        assertEquals("shared", pushed.listId)
        server.takeRequest()
        val mates = server.takeRequest()
        assertEquals("Bearer tok-mate", mates.getHeader("Authorization"))
        assertTrue(Json.decodeFromString<SyncRequest>(mates.body.readUtf8()).changes.items.isEmpty())

        assertTrue("my row is parked", item("shared-item")!!.syncBlocked)
        val theirs = item("shared-item", "mate")!!
        assertFalse("the mate's row of the same item is not", theirs.syncBlocked)
        assertEquals("todo", theirs.status.value)
        assertEquals(0, syncStatus.accounts.value.getValue("mate").blockedCount)
    }

    @Test
    fun `removing one account leaves the other's row of a shared list, and its cursor, alone`() = runTest {
        pointAtServer()
        mateOnTheSameServer()
        pullSharedListIntoBoth()
        val auth = AuthRepositoryImpl(
            accounts.sessions, accounts.registry, accounts.secrets, accounts.secrets, db,
            deviceName = "Test device",
        )

        auth.removeAccount(TEST_ACCOUNT_ID)
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        assertNull(db.listDao().getByServerId(TEST_ACCOUNT_ID, "shared"))
        assertNotNull(list("shared", "mate"))
        assertNotNull(item("shared-item", "mate"))
        val next = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals("the mate's cursor still describes what it holds", 9L, next.cursor)
    }

    @Test
    fun `an item pulled for a list this account does not hold gets a hidden stub of that account`() = runTest {
        pointAtServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(
                    cursor = 3,
                    lists = emptyList(),
                    items = listOf(itemJson(id = "stray", listId = "unseen", name = "Rope", lastTouchedBy = null)),
                ),
            ),
        )

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        val stub = list("unseen")!!
        assertEquals(TEST_ACCOUNT_ID, stub.accountId)
        assertTrue("hidden", stub.deleted.value)
        assertFalse("nothing to push", stub.dirty)
        assertEquals("every clock 0, so the real list wins every field", 0L, stub.name.updatedAt + stub.deleted.updatedAt)
        assertEquals(stub.localId, item("stray")!!.listLocalId)
        assertEquals(TEST_ACCOUNT_ID, item("stray")!!.accountId)

        // The list itself arrives later and fills the stub in: the same row, not a second one.
        server.enqueue(MockResponse().setResponseCode(200).setBody(syncResponseJson(cursor = 4, lists = listOf(listJson("unseen", "Climbing")), items = emptyList())))
        assertTrue(syncEngine.syncNow() is SyncResult.Success)
        val filled = list("unseen")!!
        assertEquals(stub.localId, filled.localId)
        assertEquals("Climbing", filled.name.value)
        assertFalse(filled.deleted.value)
    }

    @Test
    fun `a write to a closed list drops the item and asks for the list again, by its server id`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("late", "Taxi", dirty = true))
        server.enqueue(
            MockResponse().setResponseCode(422).setBody("""{"error": "list_closed", "message": "closed", "row_id": "late"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        assertNull(item("late"))
        server.takeRequest()
        val retry = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(listOf("list-1"), retry.fullLists)
    }

    @Test
    fun `a refused tombstone on an expenses list keeps the row and its items, and the snapshot wins`() = runTest {
        pointAtServer()
        db.listDao().upsert(dummyList("trip", "Trip", dirty = true, at = 2_000L).copy(deleted = true.toLww("this-device", 2_000L)))
        db.itemDao().upsert(dummyItem("dinner", "Dinner", dirty = false, list = "trip"))
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"error": "cannot_delete_expense_list", "message": "no", "row_id": "trip"}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(cursor = 2, lists = listOf(expensesListJson("trip", "Trip (server)", at = 1_500L, closeVotes = "[]")), items = emptyList()),
            ),
        )

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        server.takeRequest()
        val retry = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(listOf("trip"), retry.fullLists)
        assertTrue("nothing of the refused tombstone goes out again", retry.changes.lists.isEmpty())
        val trip = list("trip")!!
        assertEquals("the same row: its items and the screens still find it", localId("trip"), trip.localId)
        assertFalse(trip.deleted.value)
        assertEquals("the snapshot wins even with clocks older than the local edit", "Trip (server)", trip.name.value)
        assertFalse(trip.dirty)
        assertEquals(trip.localId, item("dinner")!!.listLocalId)
    }

    // ---- guards and the account lock (T-298) -------------------------------------------

    @Test
    fun `a signed-in account whose token is gone is signed out and sends nothing (T-298)`() = runTest {
        pointAtServer()
        accounts.secrets.setToken(TEST_ACCOUNT_ID, null)

        assertTrue(syncEngine.syncNow() is SyncResult.Unauthorized)

        assertEquals(0, server.requestCount)
        assertFalse(accounts.registry.get(TEST_ACCOUNT_ID)!!.signedIn)
    }

    @Test
    fun `a local account is never synced, even when asked for by id (T-298)`() = runTest {
        accounts.registry.add(
            AccountEntity(
                id = "on-device",
                kind = AccountEntity.KIND_LOCAL,
                serverUrl = null,
                accountId = null,
                email = null,
                label = "This phone",
                signedIn = true,
            ),
        )
        accounts.secrets.setToken("on-device", "never-used")

        assertTrue(syncEngine.syncAccount("on-device") is SyncResult.Unauthorized)
        assertTrue(syncEngine.syncNow() is SyncResult.Unauthorized)
    }

    @Test
    fun `the local area's rows are never sent and never counted as pending (T-293)`() = runTest {
        pointAtServer()
        val local = accounts.registry.addLocal()!!
        db.listDao().upsert(dummyList("hardware", "Hardware", dirty = true, accountId = local.id).copy(kind = "shopping".toLww("this-device", 1_000L)))
        db.itemDao().upsert(dummyItem("nails", "Nails", dirty = true, list = "hardware", accountId = local.id))
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))

        syncEngine.seedStatus()

        assertEquals("the server account's one item", 1, syncStatus.state.value.pendingCount)
        assertFalse(local.id in syncStatus.accounts.value)

        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("item-1"))
        assertFalse("nothing of the local area goes out", sent.contains("nails") || sent.contains("hardware"))
        assertEquals(1, server.requestCount)
        assertTrue("still unsent, and never to be", db.itemDao().get(localId("nails"))!!.dirty)
        assertFalse(local.id in syncStatus.accounts.value)
    }

    /** A shopping list of [accountId], clean or [dirty], with a roster from the server. */
    private fun shoppingList(id: String, accountId: String, dirty: Boolean = false): ListEntity =
        dummyList(id, id, dirty = dirty, accountId = accountId).copy(
            kind = "shopping".toLww("this-device", 1_000L),
            currency = null.toLwwOptional("this-device", 1_000L),
            membersJson = """[{"account_id":"acct-me","email":"me@example.com","initials":"ME"},{"account_id":"acct-you","email":"you@example.com","initials":"YO"}]""",
        )

    /** Copies [sourceLocalId] and its items into [targetAccountId] as List properties does (T-294). */
    private suspend fun copyList(sourceLocalId: String, targetAccountId: String): String {
        val deviceId = DeviceIdProvider { "this-device" }
        val copyId = org.p23q.shoppinglist.core.repo.ListsRepo(db, deviceId, FakeSyncTrigger()).duplicate(sourceLocalId, "(Copy)", targetAccountId)!!
        ItemsRepo(db, deviceId, FakeSyncTrigger()).duplicateForList(sourceLocalId, copyId)
        return copyId
    }

    @Test
    fun `a shared list copied into the local area has no roster and never goes out (T-294)`() = runTest {
        pointAtServer()
        val local = accounts.registry.addLocal()!!
        db.listDao().upsert(shoppingList("shared", TEST_ACCOUNT_ID))
        db.itemDao().upsert(dummyItem("tent", "Tent", dirty = false, list = "shared"))

        val copyId = copyList(localId("shared"), local.id)
        val copy = db.listDao().get(copyId)!!
        val copiedItem = db.itemDao().activeItemsForListOnce(copyId).single()
        assertEquals("[]", copy.membersJson)

        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        val sent = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals("nothing to push: the source is clean and the copy is local", emptyList<String>(), sent.changes.lists.map { it.id })
        assertEquals(emptyList<String>(), sent.changes.items.map { it.id })
        assertEquals(1, server.requestCount)
        assertTrue(db.listDao().get(copyId)!!.dirty)
        assertEquals(local.id, copiedItem.accountId)
        assertFalse("the shared source is untouched", list("shared")!!.dirty || item("tent")!!.dirty)
    }

    @Test
    fun `a local list copied into a server account pushes exactly the copied rows (T-294)`() = runTest {
        pointAtServer()
        val local = accounts.registry.addLocal()!!
        db.listDao().upsert(shoppingList("hardware", local.id, dirty = true).copy(membersJson = "[]"))
        db.itemDao().upsert(dummyItem("nails", "Nails", dirty = true, list = "hardware", accountId = local.id))
        db.itemDao().upsert(dummyItem("screws", "Screws", dirty = true, list = "hardware", accountId = local.id))

        val copyId = copyList(localId("hardware"), TEST_ACCOUNT_ID)
        val copy = db.listDao().get(copyId)!!
        val copiedItems = db.itemDao().activeItemsForListOnce(copyId)

        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        val sent = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(listOf(copy.serverId), sent.changes.lists.map { it.id })
        assertEquals(copiedItems.map { it.serverId }.toSet(), sent.changes.items.map { it.id }.toSet())
        assertEquals(2, sent.changes.items.size)
        assertTrue(sent.changes.items.all { it.listId == copy.serverId })
        assertTrue("the local source stays unsent", db.listDao().get(localId("hardware"))!!.dirty)
    }

    /** T-298: nothing tested the catch in syncNow; an exception syncAccount does not handle itself. */
    @Test
    fun `an account whose run throws does not cost the next account its sync (T-298)`() = withSecondServer { other ->
        pointAtServer()
        secondAccount(other)
        // A 200 that is not JSON: the decoder throws, which syncAccount does not catch.
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>portal</html>"))
        other.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Failed)
        assertEquals("the second account still synced", 1, other.requestCount)
        assertEquals(7L, accounts.registry.get("second")!!.syncCursor)
        assertNotNull(syncStatus.accounts.value.getValue(TEST_ACCOUNT_ID).lastError)
        assertFalse(syncStatus.accounts.value.getValue(TEST_ACCOUNT_ID).inProgress)
    }

    /**
     * T-298: a removal that lands between a sync's request and its merge left the merge writing
     * lists for an account that no longer exists, and syncNow's catch then brought the removed
     * account's status back. The removal now waits for the sync.
     */
    @Test
    fun `removing an account while its sync is out waits for it, and nothing of the account comes back`() = runBlocking<Unit> {
        pointAtServer()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(200).setBody(
                    syncResponseJson(cursor = 6, lists = listOf(listJson(id = "new-list", name = "Shared")), items = emptyList()),
                )
            }
        }
        val auth = AuthRepositoryImpl(
            accounts.sessions, accounts.registry, accounts.secrets, accounts.secrets, db,
            deviceName = "Test device",
        )

        val sync = async(Dispatchers.IO) { syncEngine.syncNow() }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        val removal = async(Dispatchers.IO) { auth.removeAccount(TEST_ACCOUNT_ID) }
        delay(300)
        val removedDuringTheRequest = removal.isCompleted
        release.countDown()
        sync.await()
        removal.await()

        assertFalse("the removal waited for the sync", removedDuringTheRequest)
        assertNull(accounts.registry.get(TEST_ACCOUNT_ID))
        assertNull(list("new-list"))
        assertNull(list("list-1"))
        assertFalse("its status is not brought back", syncStatus.accounts.value.containsKey(TEST_ACCOUNT_ID))
    }

    // ---- stable local ids (T-304) ----------------------------------------------------

    @Test
    fun `a muted list pulled again after a re-base keeps its local id, so it stays muted (T-304)`() = runTest {
        pointAtServer()
        val file = File.createTempFile("sync_engine_mutes", ".preferences_pb").apply { deleteOnExit() }
        val prefs = org.p23q.shoppinglist.data.notify.NotificationPrefsStore(PreferenceDataStoreFactory.create { file })
        val pull = { cursor: Long ->
            syncResponseJson(
                cursor = cursor,
                lists = listOf(listJson(id = "trip", name = "Trip")),
                items = listOf(itemJson(id = "tent", listId = "trip", name = "Tent", lastTouchedBy = null)),
            )
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(pull(5)))
        assertTrue(syncEngine.syncNow() is SyncResult.Success)
        val before = list("trip")!!
        val itemBefore = item("tent")!!
        prefs.setListMuted(before.localId, muted = true)
        accounts.secrets.lastOpenedListId = before.localId

        // The cursor has fallen behind: the clean rows are dropped and the cursor-0 pull brings them back.
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error": "full_resync_required", "message": "old"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(pull(9)))
        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        val after = list("trip")!!
        assertEquals("the first row for a server id takes it as its local id", "trip", before.localId)
        assertEquals(before.localId, after.localId)
        assertEquals(itemBefore.localId, item("tent")!!.localId)
        assertEquals(after.localId, item("tent")!!.listLocalId)
        assertTrue("still muted", after.localId in prefs.mutedListIds.first())
        assertEquals("the last-opened list still names it", after.localId, accounts.secrets.lastOpenedListId?.let { db.listDao().get(it)?.localId })
    }

    @Test
    fun `two accounts sharing a list get its server id and a fresh id as local ids (T-304)`() = runTest {
        pointAtServer()
        mateOnTheSameServer()

        pullSharedListIntoBoth()

        assertEquals("the first account to pull it", "shared", list("shared")!!.localId)
        assertEquals("shared-item", item("shared-item")!!.localId)
        val theirs = list("shared", "mate")!!.localId
        val theirItem = item("shared-item", "mate")!!.localId
        // The second holds a fresh one: the server id is taken as a local id.
        assertNotEquals("shared", theirs)
        assertNotEquals("shared-item", theirItem)
        java.util.UUID.fromString(theirs)
        java.util.UUID.fromString(theirItem)
    }

    @Test
    fun `a hidden stub list takes its server id as its local id too (T-304)`() = runTest {
        pointAtServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(cursor = 3, lists = emptyList(), items = listOf(itemJson(id = "stray", listId = "unseen", name = "Rope", lastTouchedBy = null))),
            ),
        )

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        assertEquals("unseen", list("unseen")!!.localId)
    }

    @Test
    fun `a list created on this phone keeps its local id when a re-base pulls it back (T-304)`() = runTest {
        pointAtServer()
        val created = testListsRepo(db).create(TEST_ACCOUNT_ID, "Mine")
        val itemId = ItemsRepo(db, DeviceIdProvider { "this-device" }, FakeSyncTrigger()).createItem(created, "Soap")
        // Pushed and acknowledged long ago: nothing left to push, so the re-base drops both rows.
        db.listDao().upsert(db.listDao().get(created)!!.copy(dirty = false))
        db.itemDao().upsert(db.itemDao().get(itemId)!!.copy(dirty = false))
        val serverId = db.listDao().get(created)!!.serverId
        val itemServerId = db.itemDao().get(itemId)!!.serverId
        setCursor(50)
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error": "full_resync_required", "message": "old"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                syncResponseJson(
                    cursor = 51,
                    lists = listOf(listJson(id = serverId, name = "Mine")),
                    items = listOf(itemJson(id = itemServerId, listId = serverId, name = "Soap", lastTouchedBy = null)),
                ),
            ),
        )

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        assertEquals(created, list(serverId)!!.localId)
        assertEquals(itemId, item(itemServerId)!!.localId)
    }

    // ---- the protocol of a server this phone never asked (T-304) ----------------------

    @Test
    fun `an account with no protocol stored asks its server once, with no token, after a sync (T-304)`() = runTest {
        pointAtServer()
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(serverProtocol = null) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version": "9.0.0", "download_url": "https://x/a.apk", "protocol": 4}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        assertTrue(syncEngine.syncNow() is SyncResult.Success)
        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        assertEquals(4, accounts.registry.get(TEST_ACCOUNT_ID)!!.serverProtocol)
        assertEquals("/api/v1/sync", server.takeRequest().path)
        val ask = server.takeRequest()
        assertEquals("/api/v1/app-version", ask.path)
        assertNull(ask.getHeader("Authorization"))
        assertEquals("not asked again", "/api/v1/sync", server.takeRequest().path)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a server without an app package gives its protocol in the 404 (T-304)`() = runTest {
        pointAtServer()
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(serverProtocol = null) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "no_app_package", "message": "none", "protocol": 3}"""))

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        assertEquals(3, accounts.registry.get(TEST_ACCOUNT_ID)!!.serverProtocol)
        assertTrue("the account is not signed out or outdated by it", accounts.registry.get(TEST_ACCOUNT_ID)!!.let { it.signedIn && !it.outdated })
    }

    @Test
    fun `an answer that names no protocol is not asked for again in this process (T-304)`() = runTest {
        pointAtServer()
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(serverProtocol = null) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version": "9.0.0", "download_url": "https://x/a.apk"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        assertTrue(syncEngine.syncNow() is SyncResult.Success)
        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        assertNull(accounts.registry.get(TEST_ACCOUNT_ID)!!.serverProtocol)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a signed-out account does not hold the status at not synced yet, and its pending rows still count (T-304)`() = runTest {
        pointAtServer()
        accounts.add("https://elsewhere.example.test/", id = "gone", token = null, accountId = "acc-gone")
        db.listDao().upsert(dummyList("list-g", "Old", dirty = false, at = 0L, accountId = "gone"))
        db.itemDao().upsert(dummyItem("g-item", "Tea", dirty = true, list = "list-g", accountId = "gone"))
        syncEngine.seedStatus()
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPull))

        assertTrue(syncEngine.syncNow() is SyncResult.Success)

        val state = syncStatus.state.value
        assertNotNull(state.lastSyncAt)
        assertNull(state.lastError)
        assertEquals(1, state.pendingCount)
    }
}
