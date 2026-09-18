package org.p23q.shoppinglist.ui.expense

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.Expense
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ListKind
import org.p23q.shoppinglist.data.ListMember
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.SessionEvents
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.sync.SyncResult
import org.p23q.shoppinglist.data.sync.Syncer
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The expense list and balances screens (T-154).
 *
 * Rendered directly with a real ViewModel over an in-memory database — the screens are Hilt-free
 * by design (T-127), which is what makes this possible at all.
 */
@RunWith(RobolectricTestRunner::class)
class ExpenseScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: AppDb
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listsRepo: ListsRepo
    private lateinit var listId: String

    private val me = "acct-me"
    private val other = "acct-other"

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            // Runs DAO calls inline so the screen has its data by waitForIdle (T-29).
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        listId = listsRepo.createList("Trip", ListKind.EXPENSES, currency = "EUR")
        setMembers(me, other)
    }

    private suspend fun setMembers(vararg ids: String) {
        // Initials from the part after the dash: "acct-me" and "acct-other" both start "AC".
        val members = ids.map {
            ListMember(it, "$it@example.com", it.substringAfter('-').take(2).uppercase())
        }
        val list = listsRepo.getById(listId)!!
        db.listDao().upsert(list.copy(membersJson = Json.encodeToString(members)))
    }

    private fun viewModel(syncer: Syncer = Syncer { SyncResult.Success(0, 0, 0, 0) }) = ExpenseListViewModel(
        SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
        itemsRepo,
        listsRepo,
        // Voting is not what these tests are about; the provider is never asked for an Api.
        ApiProvider(
            serverConfig = ServerConfig(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("expense_screens_server_config", ".preferences_pb")
                        .apply { deleteOnExit() }
                },
            ),
            authInterceptor = AuthInterceptor(TokenProvider { null }),
            errorInterceptor = ErrorInterceptor(Json { ignoreUnknownKeys = true }, SessionEvents()),
            json = Json { ignoreUnknownKeys = true },
        ),
        syncer,
        FakeSessionState().apply { accountId = me },
    )

    private fun dinner(paidBy: String = me) = Expense(
        paidBy = mapOf(paidBy to "64.00"),
        equalBy = true,
        paidFor = mapOf(me to "32.00", other to "32.00"),
        equalFor = true,
        date = "2026-09-17",
    )

    // ---- the list screen ------------------------------------------------------

    @Test
    fun `shows each expense with what it cost, who paid and who for`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())

        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenBalances = {},
                onOpenListProps = {},
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Dinner").assertIsDisplayed()
        // Twice over: once as this row's amount, once as the list's total in the summary card.
        composeTestRule.onAllNodesWithText("64.00 EUR").assertCountEquals(2)
        // Everyone on the list is covered, so the row says so rather than listing them.
        composeTestRule.onNodeWithText("paid by ME · for everyone").assertIsDisplayed()
        // Once, as the heading of that day's group rather than on every row (T-168).
        composeTestRule.onAllNodesWithText("2026-09-17").assertCountEquals(1)
        // Add is the floating button every list has now (T-168).
        composeTestRule.onNodeWithContentDescription("Add expense").assertIsDisplayed()
    }

    @Test
    fun `shows the total and my balance, and opens balances when tapped`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())
        var openedBalances = false

        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenBalances = { openedBalances = true },
                onOpenListProps = {},
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Total spent").assertIsDisplayed()
        // I paid 64 and owe 32, so the list owes me 32.
        composeTestRule.onNodeWithText("32.00 EUR").assertIsDisplayed()

        composeTestRule.onNodeWithText("Your balance").performClick()
        assertEquals(true, openedBalances)
    }

    @Test
    fun `says so when there is nothing on the list yet`() = runBlocking<Unit> {
        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenBalances = {},
                onOpenListProps = {},
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("No expenses yet. Add one to get started.").assertIsDisplayed()
    }

    @Test
    fun `tapping an expense opens it for editing`() = runBlocking<Unit> {
        val itemId = itemsRepo.createExpense(listId, "Taxi", dinner())
        var edited: String? = null

        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = { edited = it },
                onOpenBalances = {},
                onOpenListProps = {},
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Taxi").performClick()

        assertEquals(itemId, edited)
    }

    @Test
    fun `a list of one shows what was spent but no balance`() = runBlocking<Unit> {
        setMembers(me)
        itemsRepo.createExpense(
            listId,
            "Coffee",
            Expense(mapOf(me to "4.20"), true, mapOf(me to "4.20"), true, "2026-09-17"),
        )

        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenBalances = {},
                onOpenListProps = {},
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Total spent").assertIsDisplayed()
        // Always zero on a list of one, so saying it would be noise.
        composeTestRule.onNodeWithText("Your balance").assertDoesNotExist()
    }

    // ---- the balances screen --------------------------------------------------

    @Test
    fun `balances name everyone involved and what they paid`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())

        composeTestRule.setContent { BalancesScreen(viewModel = viewModel()) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Total spent: 64.00 EUR").assertIsDisplayed()
        composeTestRule.onNodeWithText("$me@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("$other@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("paid 64.00 · share 32.00").assertIsDisplayed()
        // Once as my balance and once as the transfer that settles it (T-165).
        composeTestRule.onAllNodesWithText("32.00 EUR").assertCountEquals(2)
        composeTestRule.onNodeWithText("-32.00 EUR").assertIsDisplayed()
    }

    @Test
    fun `someone who has left keeps their balance, numbered rather than named`() = runBlocking<Unit> {
        itemsRepo.createExpense(
            listId,
            "Old dinner",
            Expense(mapOf("acct-gone" to "20.00"), true, mapOf(me to "10.00", "acct-gone" to "10.00"), true, "2026-09-16"),
        )

        composeTestRule.setContent { BalancesScreen(viewModel = viewModel()) }
        composeTestRule.waitForIdle()

        // Only an account id remains, so there is no name or email to show.
        composeTestRule.onNodeWithText("Former member 1").assertIsDisplayed()
        // Once as their balance and once as the transfer that would settle it (T-165).
        composeTestRule.onAllNodesWithText("10.00 EUR").assertCountEquals(2)
        composeTestRule.onNodeWithText("-10.00 EUR").assertIsDisplayed()
    }

    // ---- settling up (T-165) ---------------------------------------------------

    /** The vote state normally arrives from the server on the list row; seed it directly here. */
    private suspend fun setClosing(closeVotes: List<String> = emptyList(), closedAt: Long? = null) {
        val list = listsRepo.getById(listId)!!
        db.listDao().upsert(list.copy(closeVotesJson = Json.encodeToString(closeVotes), closedAt = closedAt))
    }

    private fun showBalances(onRecord: (ExpensePrefill) -> Unit = {}) {
        composeTestRule.setContent { BalancesScreen(onRecord = onRecord, viewModel = viewModel()) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `settle up says who pays whom, and Record hands over a pre-filled settlement`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())
        var recorded: ExpensePrefill? = null

        showBalances(onRecord = { recorded = it })

        composeTestRule.onNodeWithText("Settle up").assertIsDisplayed()
        composeTestRule.onNodeWithText("$other@example.com pays $me@example.com").assertIsDisplayed()
        // Once as my balance, once as the transfer.
        composeTestRule.onAllNodesWithText("32.00 EUR").assertCountEquals(2)

        composeTestRule.onNodeWithText("Reimburse").performClick()
        val prefill = checkNotNull(recorded)
        assertEquals("Settlement", prefill.name)
        assertEquals(mapOf(other to "32.00"), prefill.expense.paidBy)
        assertEquals(mapOf(me to "32.00"), prefill.expense.paidFor)
        assertEquals(true, prefill.expense.equalBy)
        assertEquals(true, prefill.expense.equalFor)
    }

    @Test
    fun `nobody can record a settlement with someone who has left`() = runBlocking<Unit> {
        itemsRepo.createExpense(
            listId,
            "Old dinner",
            Expense(mapOf("acct-gone" to "20.00"), true, mapOf(me to "10.00", "acct-gone" to "10.00"), true, "2026-09-16"),
        )

        showBalances()

        composeTestRule.onNodeWithText("$me@example.com pays Former member 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reimburse").assertDoesNotExist()
    }

    @Test
    fun `a voter's amounts are frozen, so nothing involving them can be recorded`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())
        setClosing(closeVotes = listOf(other))

        showBalances()

        composeTestRule.onNodeWithText("$other@example.com pays $me@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reimburse").assertDoesNotExist()
    }

    @Test
    fun `a closed list keeps the transfers as its archive, with nothing to record`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())
        setClosing(closeVotes = listOf(me, other), closedAt = 1_758_000_000_000)

        showBalances()

        composeTestRule.onNodeWithText("$other@example.com pays $me@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reimburse").assertDoesNotExist()
    }

    @Test
    fun `says all settled when nothing is owed`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())
        itemsRepo.createExpense(listId, "Lunch", dinner(paidBy = other))

        showBalances()

        composeTestRule.onNodeWithText("All settled").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(" pays ", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a list of one has no settling up`() = runBlocking<Unit> {
        setMembers(me)
        itemsRepo.createExpense(
            listId,
            "Coffee",
            Expense(mapOf(me to "12.00"), true, mapOf(me to "12.00"), true, "2026-09-17"),
        )

        showBalances()

        composeTestRule.onNodeWithText("Total spent: 12.00 EUR").assertIsDisplayed()
        composeTestRule.onNodeWithText("Settle up").assertDoesNotExist()
    }

    // ---- pull to refresh (T-167) -----------------------------------------------

    @Test
    fun `pulling the expense list down syncs, as on the other lists`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())
        var syncs = 0
        val model = viewModel(syncer = Syncer { syncs++; SyncResult.Success(0, 0, 0, 0) })
        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenBalances = {},
                onOpenListProps = {},
                viewModel = model,
            )
        }
        composeTestRule.waitForIdle()

        // Well past the refresh threshold, starting on a row inside the list.
        composeTestRule.onNodeWithText("Dinner").performTouchInput {
            swipeDown(startY = top, endY = top + 800f, durationMillis = 400)
        }
        composeTestRule.waitForIdle()

        assertEquals(1, syncs)
        assertEquals(false, model.uiState.value.isRefreshing)
    }

    @Test
    fun `an empty expense list can still be pulled down`() = runBlocking<Unit> {
        var syncs = 0
        val model = viewModel(syncer = Syncer { syncs++; SyncResult.Success(0, 0, 0, 0) })
        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenBalances = {},
                onOpenListProps = {},
                viewModel = model,
            )
        }
        composeTestRule.waitForIdle()

        // An empty list is exactly when someone pulls to see whether anything has arrived.
        composeTestRule.onNodeWithText("No expenses yet. Add one to get started.").performTouchInput {
            swipeDown(startY = top, endY = top + 800f, durationMillis = 400)
        }
        composeTestRule.waitForIdle()

        assertEquals(1, syncs)
    }
}
