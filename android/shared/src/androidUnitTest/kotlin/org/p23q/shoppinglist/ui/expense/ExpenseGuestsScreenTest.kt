package org.p23q.shoppinglist.ui.expense

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.ListGuest
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.ListMember
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.JoinedLists
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.testListAccounts
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A ledger with guests on screen (T-364): rows label stored keys, figures fold claimed guests into
 * their claimants, balances mark guests, and the chooser after a join.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ExpenseGuestsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: AppDb
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listsRepo: ListsRepo
    private lateinit var listId: String
    private val server = MockWebServer()
    private val requests = mutableListOf<String>()
    private val joined = JoinedLists()
    private var viewModel: ExpenseListViewModel? = null

    private val me = "acct-me"
    private val other = "acct-other"
    private val dave = ListGuest("g:dave", "Dave", "DA")
    private val eve = ListGuest("g:eve", "Eve", "EV", locked = true)

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += "${request.method} ${request.path}" }
                return if (request.path.orEmpty().endsWith("/claim")) {
                    MockResponse().setResponseCode(200)
                        .setBody("""{"id": "g:dave", "name": "Dave", "initials": "DA", "locked": false, "claimed_by": "$me", "created_at": 1}""")
                } else {
                    MockResponse().setResponseCode(404).setBody("""{"error": "not_found", "message": "no"}""")
                }
            }
        }
        server.start()
        db.insertTestAccount(testAccount(serverUrl = server.url("/").toString()).copy(serverProtocol = 4))
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        listId = listsRepo.create(TEST_ACCOUNT_ID, "Trip", ListKind.EXPENSES, currency = "EUR")
        setGuests(listOf(dave, eve))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun setGuests(guests: List<ListGuest>) {
        val members = listOf(me, other).map { ListMember(it, "$it@example.com", it.substringAfter('-').take(2).uppercase()) }
        val list = listsRepo.getById(listId)!!
        db.listDao().upsert(list.copy(membersJson = Json.encodeToString(members), guestsJson = Json.encodeToString(guests)))
    }

    private fun show() {
        val model = ExpenseListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            testListAccounts(db, listsRepo),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            joined,
        )
        viewModel = model
        composeTestRule.setContent {
            ExpenseListScreen(onAddExpense = {}, onEditExpense = {}, onOpenListProps = {}, viewModel = model)
        }
        composeTestRule.waitForIdle()
    }

    private fun waitFor(text: String) {
        repeat(50) {
            composeTestRule.waitForIdle()
            if (composeTestRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(100)
        }
    }

    @Test
    fun `a row names an unclaimed guest by name and a claimed one by name and claimant`() = runBlocking<Unit> {
        setGuests(listOf(dave, eve.copy(locked = false, claimedBy = other)))
        itemsRepo.createExpense(listId, "Dinner", Expense(mapOf(dave.id to "30.00"), true, mapOf(me to "15.00", eve.id to "15.00"), true, "2026-10-31"))
        show()

        composeTestRule.onNodeWithText("Dave", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Eve · OT", substring = true).assertIsDisplayed()
    }

    @Test
    fun `my effect and balance include what a guest I claimed did`() = runBlocking<Unit> {
        setGuests(listOf(dave.copy(claimedBy = me)))
        // Dave paid 20 for other: since I am Dave, I am owed 20.
        itemsRepo.createExpense(listId, "Taxi", Expense(mapOf(dave.id to "20.00"), true, mapOf(other to "20.00"), true, "2026-10-31"))
        show()

        val state = viewModel!!.uiState.value
        assertEquals(listOf(2000L, -2000L), state.balances.map { it.balanceCents })
        assertEquals(listOf(me, other), state.balances.map { it.accountId })
        // The figure on the row is mine, though the row names Dave: it and my balance.
        composeTestRule.onAllNodesWithText("+€20.00").assertCountEquals(2)
    }

    @Test
    fun `balances mark a guest, say a locked one is locked, and do not offer to reimburse it`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", Expense(mapOf(me to "30.00"), true, mapOf(me to "10.00", dave.id to "10.00", eve.id to "10.00"), true, "2026-10-31"))
        show()
        composeTestRule.onNodeWithText("Balances").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("amounts locked").assertIsDisplayed()
        // Marked: the outline badge, which says "guest" to a screen reader.
        composeTestRule.onNodeWithContentDescription("Dave (guest)", useUnmergedTree = true).assertExists()
        val state = viewModel!!.uiState.value
        val toDave = state.transfers.first { it.from == dave.id }
        val toEve = state.transfers.first { it.from == eve.id }
        assertTrue(state.canRecord(toDave))
        assertFalse(state.canRecord(toEve))
    }

    @Test
    fun `a refusal naming a guest says its amounts are locked`() = runBlocking<Unit> {
        val id = itemsRepo.createExpense(listId, "Dinner", Expense(mapOf(me to "10.00"), true, mapOf(eve.id to "10.00"), true, "2026-10-31"))
        db.itemDao().blockRow(id, "participant_frozen", eve.id)
        show()

        composeTestRule.onNodeWithText("Eve: amounts locked").assertIsDisplayed()
    }

    @Test
    fun `after a join the chooser asks, and picking a guest claims it`() = runBlocking<Unit> {
        joined.mark(listId)
        show()

        composeTestRule.onNodeWithText("Are you one of these?").assertIsDisplayed()
        composeTestRule.onNodeWithText("No, I'm new").assertIsDisplayed()
        composeTestRule.onNodeWithTag("guest-choice-${dave.id}").performClick()
        repeat(50) {
            composeTestRule.waitForIdle()
            if (!viewModel!!.uiState.value.isChooserOpen) return@repeat
            Thread.sleep(100)
        }

        val serverId = listsRepo.getById(listId)!!.serverId
        assertEquals(listOf("POST /api/v1/lists/$serverId/guests/g:dave/claim"), synchronized(requests) { requests.toList() })
        composeTestRule.onNodeWithText("Are you one of these?").assertDoesNotExist()
    }

    @Test
    fun `no, I'm new closes the chooser, and it is asked once per join`() = runBlocking<Unit> {
        joined.mark(listId)
        show()
        composeTestRule.onNodeWithText("No, I'm new").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Are you one of these?").assertDoesNotExist()
        assertFalse(joined.consume(listId))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `without a join there is no chooser`() = runBlocking<Unit> {
        show()

        composeTestRule.onNodeWithText("Are you one of these?").assertDoesNotExist()
    }

    @Test
    fun `against a protocol-3 server there is no chooser`() = runBlocking<Unit> {
        db.accountDao().update(testAccount(serverUrl = server.url("/").toString()).copy(serverProtocol = 3))
        joined.mark(listId)
        show()

        composeTestRule.onNodeWithText("Are you one of these?").assertDoesNotExist()
    }
}
