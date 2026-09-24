package org.p23q.shoppinglist.ui.overview

import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.account.AccountRegistry
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
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
    private lateinit var accounts: TestAccounts
    private val viewModels = mutableListOf<OverviewViewModel>()
    /** The account each joined list was asked of, as the app's Syncer passes it on. */
    private val joinedFor = mutableListOf<String>()
    private lateinit var syncStatus: SyncStatus
    private var syncCalls = 0
    private val syncedFullLists = mutableListOf<List<String>>()
    private lateinit var viewModel: OverviewViewModel
    private lateinit var server: MockWebServer

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
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
            .build()
        accounts = TestAccounts(db)
        runTest(mainDispatcherRule.dispatcher) { accounts.add(server.url("/").toString()) }
        val deviceId = DeviceIdProvider { "device-1" }
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        syncStatus = SyncStatus()
        viewModel = newViewModel()
    }

    /** Whether the fake sync brings the lists it is asked a snapshot of, as a pull does. */
    private var pullBringsFullLists = true

    private fun newViewModel(): OverviewViewModel {
        val syncer = object : Syncer {
            override suspend fun syncNow(fullLists: List<String>): SyncResult {
                syncCalls++
                syncedFullLists += fullLists
                return SyncResult.Success(0, 0, 0, 0)
            }

            override suspend fun syncJoined(accountId: String, serverListId: String): SyncResult {
                joinedFor += accountId
                if (pullBringsFullLists) {
                    val localId = listsRepo.create(accountId, "Joined")
                    db.listDao().upsert(db.listDao().get(localId)!!.copy(serverId = serverListId, dirty = false))
                }
                return syncNow(listOf(serverListId))
            }
        }
        return OverviewViewModel(listsRepo, itemsRepo, accounts.registry, accounts.sessions, accounts.secrets, syncer, syncStatus)
            .also(viewModels::add)
    }

    private suspend fun setIgnored(ids: Set<String>, accountId: String = TEST_ACCOUNT_ID) {
        accounts.registry.update(accountId) { it.copy(ignoredInviteIdsJson = AccountRegistry.encodeIds(ids)) }
    }

    private fun storedIgnored(accountId: String = TEST_ACCOUNT_ID): Set<String> =
        AccountRegistry.decodeIds(accounts.registry.get(accountId)!!.ignoredInviteIdsJson)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
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

        assertEquals(listId, accounts.secrets.lastOpenedListId)
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
        setIgnored(setOf("b"))

        val state = newViewModel().uiState.first { it.invites.isNotEmpty() }

        assertEquals(listOf("Camping", "Chores"), state.invites.map { it.listName })
        assertEquals("AL", state.invites.first().invitedByInitials)
        assertEquals(setOf("b"), state.ignoredInviteIds[TEST_ACCOUNT_ID])
    }

    @Test
    fun `ignoring an invite is remembered on the device`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        val viewModel = newViewModel()
        viewModel.uiState.first { it.invites.isNotEmpty() }

        viewModel.ignoreInvite(TEST_ACCOUNT_ID, "a")

        assertEquals(setOf("a"), viewModel.uiState.value.ignoredInviteIds[TEST_ACCOUNT_ID])
        assertEquals(setOf("a"), storedIgnored())
    }

    @Test
    fun `an ignored id the server no longer offers is forgotten`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        setIgnored(setOf("a", "long-gone"))

        val state = newViewModel().uiState.first { it.invites.isNotEmpty() }

        assertEquals(setOf("a"), state.ignoredInviteIds[TEST_ACCOUNT_ID])
        assertEquals(setOf("a"), storedIgnored())
    }

    @Test
    fun `joining redeems with the invite's own token, pulls the list and opens it`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        val viewModel = newViewModel()
        val invite = viewModel.uiState.first { it.invites.isNotEmpty() }.invites.single()

        viewModel.joinInvite(TEST_ACCOUNT_ID, invite).join()

        // What opens is this phone's row of the joined list, found by the server's id for it (T-299).
        val localId = db.listDao().getByServerId(TEST_ACCOUNT_ID, "list-a")!!.localId
        assertNotEquals("list-a", localId)
        assertEquals(localId, viewModel.uiState.value.joinedListId)
        assertEquals(localId, accounts.secrets.lastOpenedListId)
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

        viewModel.joinInvite(TEST_ACCOUNT_ID, invite).join()

        assertNull(viewModel.uiState.value.joinedListId)
        assertNull(accounts.secrets.lastOpenedListId)
        assertEquals(UiText.res(R.string.error_offline), viewModel.uiState.value.inviteError)
        assertNull(viewModel.uiState.value.joiningInviteId)
    }

    @Test
    fun `a join for an account removed meanwhile says it failed, and does not crash (T-300)`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        val viewModel = newViewModel()
        val invite = viewModel.uiState.first { it.invites.isNotEmpty() }.invites.single()

        viewModel.joinInvite("gone", invite).join()

        assertEquals(UiText.res(R.string.redeem_msg_failed), viewModel.uiState.value.inviteError)
        assertNull(viewModel.uiState.value.joinedListId)
        assertNull(viewModel.uiState.value.joiningInviteId)
    }

    @Test
    fun `a refused join says why, in the server's words, and re-reads the inbox`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        redeemResponse = { MockResponse().setResponseCode(409).setBody("""{"error": "invite_revoked", "message": "revoked"}""") }
        val viewModel = newViewModel()
        val invite = viewModel.uiState.first { it.invites.isNotEmpty() }.invites.single()
        inboxJson = """{"invites": []}"""

        viewModel.joinInvite(TEST_ACCOUNT_ID, invite).join()

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

    // ---- several accounts (T-292) ---------------------------------------------------------------

    /** A second account on its own server, answering the inbox with [inbox] and any redeem with "list-w". */
    private suspend fun secondAccount(inbox: String = """{"invites": []}""", token: String? = "tok-work"): MockWebServer {
        val other = MockWebServer()
        other.dispatcher = object : Dispatcher() {
            // Mounted under /work/, as a second instance on a shared host would be.
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/work/api/v1/invites/pending" -> MockResponse().setBody(inbox)
                "/work/api/v1/invites/redeem" -> MockResponse().setBody("""{"list_id": "list-w"}""")
                else -> MockResponse().setResponseCode(404).setBody("""{"error": "not_found"}""")
            }
        }
        other.start()
        extraServers += other
        accounts.add(other.url("/work/").toString(), id = "work", token = token, accountId = "acct-work", email = "me@work.example")
        return other
    }

    private val extraServers = mutableListOf<MockWebServer>()

    @After
    fun shutDownExtraServers() {
        extraServers.forEach { it.shutdown() }
    }

    @Test
    fun `with two accounts there is a section per account, in the user's order, each with its own lists`() =
        runTest(mainDispatcherRule.dispatcher) {
            secondAccount()
            listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
            listsRepo.create("work", "Office supplies")
            // The user moved the work account up (the Accounts screen writes sortOrder).
            accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(sortOrder = 5) }

            val state = newViewModel().uiState.first { s -> s.accounts.size == 2 && s.lists.size == 2 && s.accounts.first().id == "work" }

            assertTrue(state.several)
            assertEquals(listOf("work", TEST_ACCOUNT_ID), state.sections.map { it.account.id })
            assertEquals(listOf("Office supplies"), state.sections[0].lists.map { it.name.value })
            assertEquals(listOf("Groceries"), state.sections[1].lists.map { it.name.value })
        }

    @Test
    fun `with one account there is one section and it is not several`() = runTest(mainDispatcherRule.dispatcher) {
        listsRepo.create(TEST_ACCOUNT_ID, "Groceries")

        val state = viewModel.uiState.first { it.accounts.isNotEmpty() && it.lists.isNotEmpty() }

        assertFalse(state.several)
        assertEquals(listOf(TEST_ACCOUNT_ID), state.sections.map { it.account.id })
    }

    @Test
    fun `a phone-only account's section comes after every server account's`() {
        val server = testAccount(id = "s").copy(sortOrder = 3)
        val local = testAccount(id = "l").copy(kind = AccountEntity.KIND_LOCAL, serverUrl = null, sortOrder = 0)
        val first = testAccount(id = "f").copy(sortOrder = 1)

        assertEquals(listOf("f", "s", "l"), overviewOrder(listOf(server, local, first)).map { it.id })
    }

    @Test
    fun `each account's invites are asked of its own server and joined through it`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        val work = secondAccount(inbox = """{"invites": [${inviteJson("w", "Desk plants")}]}""")

        val viewModel = newViewModel()
        val state = viewModel.uiState.first { it.invitesByAccount.size == 2 }
        assertEquals(listOf("Camping"), state.invitesByAccount[TEST_ACCOUNT_ID]!!.map { it.listName })
        assertEquals(listOf("Desk plants"), state.invitesByAccount["work"]!!.map { it.listName })
        assertEquals(listOf("Desk plants"), state.sections.single { it.account.id == "work" }.invites.map { it.listName })

        viewModel.joinInvite("work", state.invitesByAccount["work"]!!.single()).join()

        val redeem = (0 until work.requestCount).map { work.takeRequest() }.single { it.path == "/work/api/v1/invites/redeem" }
        assertEquals("Bearer tok-work", redeem.getHeader("Authorization"))
        assertTrue(redeem.body.readUtf8().contains(""""token":"token-w""""))
        assertEquals(listOf("work"), joinedFor)
        val localId = db.listDao().getByServerId("work", "list-w")!!.localId
        assertEquals(localId, viewModel.uiState.value.joinedListId)
    }

    @Test
    fun `an ignored invite is kept on its own account`() = runTest(mainDispatcherRule.dispatcher) {
        inboxJson = """{"invites": [${inviteJson("a", "Camping")}]}"""
        secondAccount(inbox = """{"invites": [${inviteJson("w", "Desk plants")}]}""")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.invitesByAccount.size == 2 }

        viewModel.ignoreInvite("work", "w")

        assertEquals(setOf("w"), storedIgnored("work"))
        assertEquals(emptySet<String>(), storedIgnored())
        val sections = viewModel.uiState.value.sections
        assertEquals(listOf("Desk plants"), sections.single { it.account.id == "work" }.ignoredInvites.map { it.listName })
        assertEquals(listOf("Camping"), sections.single { it.account.id == TEST_ACCOUNT_ID }.invites.map { it.listName })
    }

    @Test
    fun `a signed-out account's inbox is not asked`() = runTest(mainDispatcherRule.dispatcher) {
        val work = secondAccount(inbox = """{"invites": [${inviteJson("w", "Desk plants")}]}""", token = null)

        val viewModel = newViewModel()
        viewModel.refresh().join()

        assertEquals(0, work.requestCount)
        assertNull(viewModel.uiState.value.invitesByAccount["work"])
    }

    @Test
    fun `the new-list dialog defaults to the account of the last opened list, and creates the list there`() =
        runTest(mainDispatcherRule.dispatcher) {
            secondAccount()
            accounts.registry.update("work") { it.copy(defaultCurrency = "GBP") }
            accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(defaultCurrency = "EUR") }
            val office = listsRepo.create("work", "Office supplies")
            val viewModel = newViewModel()
            viewModel.uiState.first { it.lists.isNotEmpty() && it.accounts.size == 2 }

            // Nothing opened yet: the first account.
            viewModel.openCreateDialog()
            assertEquals(TEST_ACCOUNT_ID, viewModel.uiState.value.newListAccountId)
            assertEquals("EUR", viewModel.uiState.value.newListCurrency)
            viewModel.dismissCreateDialog()

            viewModel.openList(office)
            viewModel.openCreateDialog()
            assertEquals("work", viewModel.uiState.value.newListAccountId)
            assertEquals("GBP", viewModel.uiState.value.newListCurrency)

            // Choosing another account brings its currency, as nothing was typed over the default.
            viewModel.onNewListAccountChange(TEST_ACCOUNT_ID)
            assertEquals("EUR", viewModel.uiState.value.newListCurrency)
            viewModel.onNewListAccountChange("work")

            viewModel.onNewListNameChange("Printer paper")
            viewModel.createList()?.join()

            val created = viewModel.uiState.first { s -> s.lists.any { it.name.value == "Printer paper" } }
                .lists.single { it.name.value == "Printer paper" }
            assertEquals("work", created.accountId)
        }
}
