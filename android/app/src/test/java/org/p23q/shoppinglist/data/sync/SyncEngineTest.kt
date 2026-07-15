package org.p23q.shoppinglist.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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
import org.p23q.shoppinglist.data.api.SyncRequest
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.ItemEntity
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

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var serverConfig: ServerConfig
    private lateinit var sessionState: FakeSessionState
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

        sessionState = FakeSessionState()

        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )

        syncStatus = SyncStatus()
        syncEngine = SyncEngine(db.itemDao(), db.listDao(), apiProvider, sessionState, serverConfig, db, syncStatus, notifier)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
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
    fun `410 full_resync_required wipes the mirror and resyncs at cursor 0`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))
        sessionState.syncCursor = 999L

        server.enqueue(
            MockResponse().setResponseCode(410)
                .setBody("""{"error": "full_resync_required", "message": "cursor too old"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Success)
        assertNull("local mirror should have been wiped", db.itemDao().getById("item-1"))
        assertEquals(1L, sessionState.syncCursor)
        val firstRequest = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        val retryRequest = Json.decodeFromString<SyncRequest>(server.takeRequest().body.readUtf8())
        assertEquals(999L, firstRequest.cursor)
        assertEquals(0L, retryRequest.cursor)
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
    fun `401 surfaces as Unauthorized without touching local state`() = runTest {
        pointAtServer()
        db.itemDao().upsert(dummyItem("item-1", "Milk", dirty = true))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "invalid_token", "message": "expired"}"""))

        val result = syncEngine.syncNow()

        assertTrue(result is SyncResult.Unauthorized)
        assertTrue("dirty row should be untouched", db.itemDao().getById("item-1")!!.dirty)
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
}
