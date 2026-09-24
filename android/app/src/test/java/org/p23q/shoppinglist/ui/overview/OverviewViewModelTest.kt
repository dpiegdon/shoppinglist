package org.p23q.shoppinglist.ui.overview

import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import kotlinx.coroutines.runBlocking
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.FakeCurrentAccount
import org.p23q.shoppinglist.data.TestServerAddress
import org.p23q.shoppinglist.core.api.ApiSource
import org.p23q.shoppinglist.data.testApiSource
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.UiText
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OverviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDb
    private lateinit var listsRepo: ListsRepo
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var sessionState: FakeCurrentAccount
    private lateinit var syncStatus: SyncStatus
    private var syncCalls = 0
    private val syncedFullLists = mutableListOf<List<String>>()
    private lateinit var viewModel: OverviewViewModel
    private lateinit var server: MockWebServer
    private lateinit var apiProvider: ApiSource

    /** What the fake server answers to the inbox and redeem requests (T-233); tests reassign these. */
    private var inboxJson = """{"invites": []}"""
    private var redeemResponse: () -> MockResponse = { MockResponse().setBody("""{"list_id": "list-a"}""") }

    private fun inviteJson(id: String, listName: String) =
        """{"id": "$id", "list_id": "list-$id", "list_name": "$listName", "list_kind": "shopping",
           "invited_by_initials": "AL", "expires_at": ${System.currentTimeMillis() + 5 * 86_400_000L}, "token": "token-$id"}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/invites/pending" -> MockResponse().setBody(inboxJson)
                "/api/v1/invites/redeem" -> redeemResponse()
                else -> MockResponse().setResponseCode(404).setBody("""{"error": "not_found"}""")
            }
        }
        server.start()
        val serverConfigFile = File.createTempFile("overview_vm_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = TestServerAddress()
        kotlinx.coroutines.runBlocking { serverConfig.setServerUrl(server.url("/").toString()) }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
            .build()
        runTest(mainDispatcherRule.dispatcher) { db.insertTestAccount() }
        val deviceId = DeviceIdProvider { "device-1" }
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        sessionState = FakeCurrentAccount().apply { token = "tok-123" }
        syncStatus = SyncStatus()
        val json = Json { ignoreUnknownKeys = true }
        apiProvider = testApiSource(json, token = { sessionState.token }) { serverConfig.url }
        viewModel = newViewModel()
    }

    /** Whether the fake sync brings the lists it is asked a snapshot of, as a pull does. */
    private var pullBringsFullLists = true

    private fun newViewModel(): OverviewViewModel {
        val syncer = Syncer { fullLists ->
            syncCalls++
            syncedFullLists += fullLists
            if (pullBringsFullLists) {
                fullLists.forEach { serverId ->
                    val localId = listsRepo.create(TEST_ACCOUNT_ID, "Joined")
                    db.listDao().upsert(db.listDao().get(localId)!!.copy(serverId = serverId, dirty = false))
                }
            }
            SyncResult.Success(0, 0, 0, 0)
        }
        return OverviewViewModel(listsRepo, itemsRepo, sessionState, syncer, syncStatus, apiProvider)
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `created list appears in state, is dirty, and closes the dialog`() = runTest(mainDispatcherRule.dispatcher) {
        viewModel.onNewListNameChange("Groceries")

        viewModel.createList()?.join()

        val lists = viewModel.uiState.first { it.lists.isNotEmpty() }.lists
        assertEquals(1, lists.size)
        assertEquals("Groceries", lists.first().name.value)
        assertTrue(lists.first().dirty)
        assertFalse(viewModel.uiState.value.isCreateDialogOpen)
        assertEquals("", viewModel.uiState.value.newListName)
    }

    @Test
    fun `open item counts reflect todo items per list, excluding checked (T-42)`() = runTest(mainDispatcherRule.dispatcher) {
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        itemsRepo.createItem(listId, "Bread", status = Status.TODO)
        itemsRepo.createItem(listId, "Eggs", status = Status.CHECKED)

        val state = viewModel.uiState.first { it.openCounts[listId] == 2 }

        assertEquals(2, state.openCounts[listId])
    }

    @Test
    fun `lists are sorted case-insensitively by name (T-40)`() = runTest(mainDispatcherRule.dispatcher) {
        listsRepo.create(TEST_ACCOUNT_ID, "Zebra")
        listsRepo.create(TEST_ACCOUNT_ID, "apple")
        listsRepo.create(TEST_ACCOUNT_ID, "Mango")

        val names = viewModel.uiState.first { it.lists.size == 3 }.lists.map { it.name.value }

        assertEquals(listOf("apple", "Mango", "Zebra"), names)
    }

    @Test
    fun `blank name does not create a list`() = runTest(mainDispatcherRule.dispatcher) {
        viewModel.onNewListNameChange("   ")

        val job = viewModel.createList()

        assertNull(job)
        assertTrue(viewModel.uiState.value.lists.isEmpty())
    }

    @Test
    fun `openList persists lastOpenedListId in session state`() = runTest(mainDispatcherRule.dispatcher) {
        viewModel.onNewListNameChange("Groceries")
        viewModel.createList()?.join()
        val listId = viewModel.uiState.first { it.lists.isNotEmpty() }.lists.first().localId

        viewModel.openList(listId)

        assertEquals(listId, sessionState.lastOpenedListId)
    }

    @Test
    fun `openCreateDialog and dismissCreateDialog toggle dialog visibility`() = runTest(mainDispatcherRule.dispatcher) {
        viewModel.openCreateDialog()
        assertTrue(viewModel.uiState.value.isCreateDialogOpen)

        viewModel.dismissCreateDialog()
        assertFalse(viewModel.uiState.value.isCreateDialogOpen)
    }

    @Test
    fun `sync status flows into the ui state (T-47)`() = runTest(mainDispatcherRule.dispatcher) {
        syncStatus.account(TEST_ACCOUNT_ID).succeeded(at = 1_000L, pending = 2, blocked = 0)

        val state = viewModel.uiState.first { it.sync.lastSyncAt == 1_000L }
        assertEquals(2, state.sync.pendingCount)
        assertNull(state.attentionListId)
    }

    @Test
    fun `refresh runs a sync and clears the refreshing flag (T-36)`() = runTest(mainDispatcherRule.dispatcher) {
        viewModel.refresh().join()

        assertEquals(1, syncCalls)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `a quarantined row surfaces its list for the attention banner (T-47)`() = runTest(mainDispatcherRule.dispatcher) {
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk")
        db.itemDao().blockRow(itemId, "invalid_price", null)

        syncStatus.account(TEST_ACCOUNT_ID).failed("bad row", pending = 0, blocked = 1)

        val state = viewModel.uiState.first { it.sync.blockedCount == 1 }
        assertEquals(listId, state.attentionListId)
    }

    @Test
    fun `a quarantined list surfaces itself for the attention banner (T-198)`() = runTest(mainDispatcherRule.dispatcher) {
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Trip")
        db.listDao().blockRow(listId)

        syncStatus.account(TEST_ACCOUNT_ID).failed("refused list row", pending = 0, blocked = 1)

        val state = viewModel.uiState.first { it.sync.blockedCount == 1 }
        assertEquals(listId, state.attentionListId)
    }

    @Test
    fun `a closed expense list is marked as closed (T-181)`() = runTest(mainDispatcherRule.dispatcher) {
        val id = listsRepo.create(TEST_ACCOUNT_ID, "Trip", org.p23q.shoppinglist.core.ListKind.EXPENSES, currency = "EUR")
        val list = listsRepo.getById(id)!!
        db.listDao().upsert(list.copy(closedAt = 1_758_000_000_000))

        val summary = viewModel.uiState.first { it.expenseSummaries[id]?.closed == true }.expenseSummaries.getValue(id)

        assertEquals(true, summary.closed)
    }

    @Test
    fun `a ledger's card shows net spent, not everything that ever moved (T-245)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val id = listsRepo.create(TEST_ACCOUNT_ID, "Trip", org.p23q.shoppinglist.core.ListKind.EXPENSES, currency = "EUR")
            val dinner = org.p23q.shoppinglist.core.Expense(
                mapOf("me" to "60.00"), true, mapOf("me" to "60.00"), true, "2026-09-18",
            )
            itemsRepo.createExpense(id, "Dinner", dinner)
            // A refund comes off what was spent, and a settlement counts for nothing at all.
            itemsRepo.createExpense(
                id,
                "Deposit back",
                dinner.copy(paidBy = mapOf("me" to "20.00"), paidFor = mapOf("me" to "20.00"), type = "income"),
            )
            itemsRepo.createExpense(
                id,
                "Payback",
                dinner.copy(paidBy = mapOf("you" to "5.00"), paidFor = mapOf("me" to "5.00"), type = "transfer"),
            )

            val summary = viewModel.uiState
                .first { it.expenseSummaries[id] != null }
                .expenseSummaries.getValue(id)

            assertEquals(4000L, summary.totalCents)
        }

    @Test
    fun `a card already on screen updates when an entry is recorded, not just at start-up (T-265)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val id = listsRepo.create(TEST_ACCOUNT_ID, "Trip", org.p23q.shoppinglist.core.ListKind.EXPENSES, currency = "EUR")
            val lunch = org.p23q.shoppinglist.core.Expense(
                mapOf("me" to "10.00"), true, mapOf("me" to "10.00"), true, "2026-09-18",
            )
            itemsRepo.createExpense(id, "Lunch", lunch)

            // Let the card's first total land — recording the second entry below must be what moves
            // it, not this same one-shot read happening to already see both entries.
            viewModel.uiState.first { it.expenseSummaries[id]?.totalCents == 1000L }

            // Recording an entry touches only the items table, never the list row: a summary
            // collector driven by the lists flow alone (the bug, T-265) never sees this.
            itemsRepo.createExpense(id, "Dinner", lunch.copy(paidBy = mapOf("me" to "20.00"), paidFor = mapOf("me" to "20.00")))
            advanceUntilIdle()

            assertEquals(3000L, viewModel.uiState.value.expenseSummaries.getValue(id).totalCents)
        }

    @Test
    fun `an expense list's count is its number of expenses (T-191)`() = runTest(mainDispatcherRule.dispatcher) {
        val id = listsRepo.create(TEST_ACCOUNT_ID, "Trip", org.p23q.shoppinglist.core.ListKind.EXPENSES, currency = "EUR")
        val expense = org.p23q.shoppinglist.core.Expense(mapOf("me" to "10.00"), true, mapOf("me" to "10.00"), true, "2026-09-18")
        itemsRepo.createExpense(id, "Dinner", expense)
        itemsRepo.createExpense(id, "Taxi", expense)

        val counts = viewModel.uiState.first { it.openCounts[id] == 2 }.openCounts

        // What the web counts too: every expense, since none is ever ticked off.
        assertEquals(2, counts[id])
    }

    // ---- invites waiting for this account (T-233) ----------------------------------------------

    @Test
    fun `pending invites load into the state, those ignored before already shelved`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}, ${inviteJson("b", "Chores")}]}"""
        sessionState.ignoredInviteIds = setOf("b")

        val state = newViewModel().uiState.first { it.invites.isNotEmpty() }

        assertEquals(listOf("Camping", "Chores"), state.invites.map { it.listName })
        assertEquals("AL", state.invites.first().invitedByInitials)
        assertEquals(setOf("b"), state.ignoredInviteIds)
    }

    @Test
    fun `ignoring an invite is remembered on the device`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        val viewModel = newViewModel()
        viewModel.uiState.first { it.invites.isNotEmpty() }

        viewModel.ignoreInvite("a")

        assertEquals(setOf("a"), viewModel.uiState.value.ignoredInviteIds)
        assertEquals(setOf("a"), sessionState.ignoredInviteIds)
    }

    @Test
    fun `an ignored id the server no longer offers is forgotten`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        sessionState.ignoredInviteIds = setOf("a", "long-gone")

        val state = newViewModel().uiState.first { it.invites.isNotEmpty() }

        assertEquals(setOf("a"), state.ignoredInviteIds)
        assertEquals(setOf("a"), sessionState.ignoredInviteIds)
    }

    @Test
    fun `joining redeems with the invite's own token, pulls the list and opens it`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        val viewModel = newViewModel()
        val invite = viewModel.uiState.first { it.invites.isNotEmpty() }.invites.single()

        viewModel.joinInvite(invite).join()

        // What opens is this phone's row of the joined list, found by the server's id for it (T-299).
        val localId = db.listDao().getByServerId(TEST_ACCOUNT_ID, "list-a")!!.localId
        assertNotEquals("list-a", localId)
        assertEquals(localId, viewModel.uiState.value.joinedListId)
        assertEquals(localId, sessionState.lastOpenedListId)
        assertTrue(syncedFullLists.contains(listOf("list-a")))
        assertNull(viewModel.uiState.value.inviteError)
        val redeem = (0 until server.requestCount).map { server.takeRequest() }.single { it.path == "/api/v1/invites/redeem" }
        assertTrue(redeem.body.readUtf8().contains(""""token":"token-a""""))

        viewModel.joinedListOpened()
        assertNull(viewModel.uiState.value.joinedListId)
    }

    @Test
    fun `a join whose list the sync did not bring opens nothing and says so`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        pullBringsFullLists = false
        val viewModel = newViewModel()
        val invite = viewModel.uiState.first { it.invites.isNotEmpty() }.invites.single()

        viewModel.joinInvite(invite).join()

        assertNull(viewModel.uiState.value.joinedListId)
        assertNull(sessionState.lastOpenedListId)
        assertEquals(UiText.res(R.string.error_offline), viewModel.uiState.value.inviteError)
        assertNull(viewModel.uiState.value.joiningInviteId)
    }

    @Test
    fun `a refused join says why, in the server's words, and re-reads the inbox`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        redeemResponse = { MockResponse().setResponseCode(409).setBody("""{"error": "invite_revoked", "message": "revoked"}""") }
        val viewModel = newViewModel()
        val invite = viewModel.uiState.first { it.invites.isNotEmpty() }.invites.single()
        inboxJson = """{"invites": []}"""

        viewModel.joinInvite(invite).join()

        assertEquals(UiText.res(R.string.api_error_invite_revoked), viewModel.uiState.value.inviteError)
        assertNull(viewModel.uiState.value.joinedListId)
        viewModel.uiState.first { it.invites.isEmpty() }
    }

    @Test
    fun `an unreachable inbox leaves the section absent`() = runTest(mainDispatcherRule.dispatcher) {
        server.shutdown()

        val viewModel = newViewModel()
        viewModel.refresh().join()

        assertTrue(viewModel.uiState.value.invites.isEmpty())
        assertNull(viewModel.uiState.value.inviteError)
    }
}
