package org.p23q.shoppinglist.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.ProtocolState
import org.p23q.shoppinglist.data.api.SyncRequest
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.ItemDao
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.ListEntity
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.db.toLww
import org.p23q.shoppinglist.data.db.toLwwOptional
import org.robolectric.RobolectricTestRunner
import java.io.File

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
        override suspend fun getById(id: String): ItemEntity? {
            val row = delegate.getById(id)
            insideTheWindow()
            return row
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var serverConfig: ServerConfig
    private lateinit var sessionState: FakeSessionState
    private lateinit var syncStatus: SyncStatus
    private lateinit var syncEngine: SyncEngine
    private lateinit var protocolState: ProtocolState
    private lateinit var apiProvider: ApiProvider
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

        sessionState = FakeSessionState()

        val json = Json { ignoreUnknownKeys = true }
        // One ProtocolState for both, as Hilt hands out: the interceptor raises it, the engine
        // reads it (T-244).
        protocolState = ProtocolState()
        apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents(), protocolState),
            json = json,
        )

        syncStatus = SyncStatus()
        syncEngine = SyncEngine(
            db.itemDao(), db.listDao(), apiProvider, sessionState, serverConfig, db, syncStatus, notifier, protocolState,
        )
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        if (::db.isInitialized) db.close()
    }

    private suspend fun pointAtServer() {
        serverConfig.setServerUrl(server.url("/").toString())
        serverConfig.deviceId() // mint one so it's stable across the test
    }

    private fun dummyItem(id: String, name: String, dirty: Boolean, at: Long = 1_000L): ItemEntity = ItemEntity(
        id = id,
        listId = "list-1",
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

    private fun dummyList(id: String, name: String, dirty: Boolean, at: Long = 1_000L): ListEntity = ListEntity(
        id = id,
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
        val stored = db.itemDao().getById("item-1")!!
        assertFalse(stored.dirty)
        assertEquals(1L, sessionState.syncCursor)

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
        val stored = db.itemDao().getById("item-2")!!
        assertEquals("Bread", stored.name.value)
        assertFalse(stored.dirty)
        assertEquals(5L, sessionState.syncCursor)
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

        val stored = db.itemDao().getById("item-1")!!
        assertEquals("Milk 2%", stored.name.value)
        assertTrue("row should still be dirty since the name field wasn't actually acknowledged", stored.dirty)
    }

    @Test
    fun `410 full_resync_required re-bases the mirror and resyncs at cursor 0`() = runTest {
        pointAtServer()
        // A row the server can reproduce, and one it cannot: only the first is dropped (T-259).
        db.itemDao().upsert(dummyItem("item-synced", "Bread", dirty = false))
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))
        sessionState.syncCursor = 999L

        server.enqueue(
            MockResponse().setResponseCode(410)
                .setBody("""{"error": "full_resync_required", "message": "cursor too old"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        assertNull("a synced row is dropped for the cursor-0 pull to bring back", db.itemDao().getById("item-synced"))
        assertNotNull("an unpushed edit is not", db.itemDao().getById("item-1"))
        assertEquals(1L, sessionState.syncCursor)
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
        sessionState.syncCursor = 999L

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
        db.itemDao().blockRow("parked", "participant_frozen", "acct-other")
        sessionState.syncCursor = 999L

        server.enqueue(
            MockResponse().setResponseCode(410)
                .setBody("""{"error": "full_resync_required", "message": "cursor too old"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        val parked = db.itemDao().getById("parked")
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
        assertTrue("the rejected row is quarantined", db.itemDao().getById("bad-item")!!.syncBlocked)
        assertFalse("the healthy row is not", db.itemDao().getById("good-item")!!.syncBlocked)

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

        val parked = db.itemDao().getById("dinner")!!
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
        val parked = db.listDao().getById("list-1")!!
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
        assertTrue("the rejected list is quarantined", db.listDao().getById("bad-list")!!.syncBlocked)
        assertFalse("the healthy one is not", db.listDao().getById("good-list")!!.syncBlocked)

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
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))
        db.itemDao().upsert(dummyItem("item-2", "Bread", dirty = true))
        db.itemDao().upsert(dummyItem("parked", "Dinner", dirty = false))
        db.itemDao().blockRow("parked", "participant_frozen", "acct-other")
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
        assertTrue("dirty row should be untouched", db.itemDao().getById("item-1")!!.dirty)
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
        val item = db.itemDao().getById("item-1")!!
        assertTrue("dirty item should be untouched", item.dirty)
        assertFalse("nothing was quarantined", item.syncBlocked)
        val list = db.listDao().getById("list-1")!!
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
        assertTrue("dirty row should still be pushable", db.itemDao().getById("item-1")!!.dirty)
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
        sessionState.accountId = "acc-me"
        sessionState.syncCursor = 5
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
        assertEquals(listOf(CollaboratorChange("list-1", "Groceries", 2)), notifier.calls.single())
    }

    @Test
    fun `a pull containing only own-account and null-account rows stays silent (T-65)`() = runTest {
        pointAtServer()
        sessionState.accountId = "acc-me"
        sessionState.syncCursor = 5
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
        sessionState.accountId = "acc-me"
        sessionState.syncCursor = 0
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
        sessionState.accountId = null // session predates accountId storage
        sessionState.syncCursor = 5
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
        sessionState.accountId = null // pre-v1.2.0 session: never stored at login
        sessionState.accountEmail = "me@example.com"
        sessionState.syncCursor = 5
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

        assertEquals("acc-me", sessionState.accountId)
        assertEquals(listOf(CollaboratorChange("list-1", "Groceries", 1)), notifier.calls.single())
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
        sessionState.syncCursor = 5L

        val itemsRepo = ItemsRepo(db, DeviceIdProvider { "this-device" }, FakeSyncTrigger())
        val shopper = CoroutineScope(Dispatchers.IO)
        var tap: Job? = null
        var committedInsideTheWindow = true

        val probedEngine = SyncEngine(
            MergeWindowItemDao(db.itemDao()) {
                if (tap == null) {
                    tap = shopper.launch { itemsRepo.setStatus("item-1", Status.CHECKED) }
                    committedInsideTheWindow = withTimeoutOrNull(2_000) { tap!!.join() } != null
                }
            },
            db.listDao(), apiProvider, sessionState, serverConfig, db, syncStatus, notifier, protocolState,
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
        val stored = db.itemDao().getById("item-1")!!
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
}
