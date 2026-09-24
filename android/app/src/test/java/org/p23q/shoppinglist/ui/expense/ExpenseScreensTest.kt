package org.p23q.shoppinglist.ui.expense

import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.ExpenseType
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.ListMember
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.FakeCurrentAccount
import org.p23q.shoppinglist.data.TestServerAddress
import org.p23q.shoppinglist.core.api.ApiSource
import org.p23q.shoppinglist.data.testApiSource
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The expense list and balances screens (T-154).
 *
 * Rendered directly with a real ViewModel over an in-memory database — the screens are Hilt-free
 * by design (T-127), which is what makes this possible at all.
 */
@RunWith(RobolectricTestRunner::class)
// A phone-sized screen rather than Robolectric's default 470dp: since the balances view sits under
// the list's controls row (T-172), a three-person balance list pushes settle up below that default
// fold, and a LazyColumn does not compose rows that are off screen — so assertions about them
// failed on content that a real phone shows without scrolling.
@Config(qualifiers = "w411dp-h891dp")
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
        runBlocking { db.insertTestAccount() }
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        listId = listsRepo.create(TEST_ACCOUNT_ID, "Trip", ListKind.EXPENSES, currency = "EUR")
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
        testApiSource(Json { ignoreUnknownKeys = true }, token = { null }) { null },
        syncer,
        FakeCurrentAccount().apply { accountId = me },
    )

    private fun dinner(paidBy: String = me) = Expense(
        paidBy = mapOf(paidBy to "64.00"),
        equalBy = true,
        paidFor = mapOf(me to "32.00", other to "32.00"),
        equalFor = true,
        date = "2026-09-17",
    )

    /** A 30.00 refund received by [receivedBy] and credited to both of us (T-245). */
    private fun refund(receivedBy: String = me) = Expense(
        paidBy = mapOf(receivedBy to "30.00"),
        equalBy = true,
        paidFor = mapOf(me to "15.00", other to "15.00"),
        equalFor = true,
        date = "2026-09-17",
        type = ExpenseType.INCOME.wire,
    )

    /** [from] hands [to] money directly: a settlement, which spends nothing (T-245). */
    private fun payback(from: String = other, to: String = me, amount: String = "17.00") = Expense(
        paidBy = mapOf(from to amount),
        equalBy = true,
        paidFor = mapOf(to to amount),
        equalFor = true,
        date = "2026-09-17",
        type = ExpenseType.TRANSFER.wire,
    )

    // ---- the list screen ------------------------------------------------------

    @Test
    fun `shows each expense with what it cost, who paid and who for`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())

        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenListProps = {},
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Dinner").assertIsDisplayed()
        // Twice over: once as this row's amount, once as the list's total in the summary card.
        composeTestRule.onAllNodesWithText("€64.00").assertCountEquals(2)
        // Everyone on the list is covered, so the row says so rather than listing them.
        composeTestRule.onNodeWithText("paid by ME · for everyone").assertIsDisplayed()
        // Once, as the heading of that day's group rather than on every row (T-168).
        composeTestRule.onAllNodesWithText("Sep 17, 2026").assertCountEquals(1)
        // Add is the floating button every list has now (T-168).
        composeTestRule.onNodeWithContentDescription("Add entry").assertIsDisplayed()
    }

    @Test
    fun `shows the total and my balance, and the selector switches to balances and back`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())

        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenListProps = {},
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Net spent").assertIsDisplayed()
        // I paid 64 and owe 32, so the list owes me 32 — a credit, so signed (T-241). Twice over:
        // once as my balance in the summary card, once as what this one entry did to it (T-245).
        composeTestRule.onAllNodesWithText("+€32.00").assertCountEquals(2)

        // Balances is the other half of this screen now, behind the selector (T-172).
        composeTestRule.onNodeWithText("Balances").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("paid 64.00 · share 32.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dinner").assertDoesNotExist()
        // Adding belongs to the expenses view.
        composeTestRule.onNodeWithContentDescription("Add entry").assertDoesNotExist()

        composeTestRule.onNodeWithText("Entries").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Dinner").assertIsDisplayed()
    }

    @Test
    fun `says so when there is nothing on the list yet`() = runBlocking<Unit> {
        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenListProps = {},
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("No entries yet. Add one to get started.").assertIsDisplayed()
    }

    @Test
    fun `tapping an expense opens it for editing`() = runBlocking<Unit> {
        val itemId = itemsRepo.createExpense(listId, "Taxi", dinner())
        var edited: String? = null

        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = { edited = it },
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
                onOpenListProps = {},
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Net spent").assertIsDisplayed()
        // Always zero on a list of one, so saying it would be noise.
        composeTestRule.onNodeWithText("Your balance").assertDoesNotExist()
    }

    // ---- income and transfer rows (T-245) -------------------------------------

    /** Opens the entries view with whatever is already on the list. */
    private fun showEntries() {
        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenListProps = {},
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `an income says so, reads the other way round and counts against what was spent`() =
        runBlocking<Unit> {
            itemsRepo.createExpense(listId, "Dinner", dinner())
            itemsRepo.createExpense(listId, "Deposit back", refund())

            showEntries()

            // The type, quietly beside the title: an expense is the ordinary case and says nothing.
            composeTestRule.onNodeWithText("Income").assertIsDisplayed()
            composeTestRule.onNodeWithText("received by ME · for everyone").assertIsDisplayed()
            // Money coming in reads the other way, so the amount carries a plus.
            composeTestRule.onNodeWithText("+€30.00").assertIsDisplayed()
            // I received it and a third of it was mine, so it took 15 off what the list owes me.
            composeTestRule.onNodeWithText("-€15.00").assertIsDisplayed()
            // Net spent: 64 laid out less 30 taken in.
            composeTestRule.onNodeWithText("€34.00").assertIsDisplayed()
        }

    @Test
    fun `a transfer is named, reads from one person to another and spends nothing`() =
        runBlocking<Unit> {
            itemsRepo.createExpense(listId, "Dinner", dinner())
            itemsRepo.createExpense(listId, "Payback", payback())

            showEntries()

            composeTestRule.onNodeWithText("Transfer").assertIsDisplayed()
            composeTestRule.onNodeWithText("OT → ME").assertIsDisplayed()
            // A settlement moves money without the group spending a thing, so the net stays 64.
            composeTestRule.onAllNodesWithText("€64.00").assertCountEquals(2)
            // Plain, not signed: what the group moved is nobody's position.
            composeTestRule.onNodeWithText("€17.00").assertIsDisplayed()
            // It was paid to me, so it takes 17 off what I am owed.
            composeTestRule.onNodeWithText("-€17.00").assertIsDisplayed()
        }

    @Test
    fun `an entry that does nothing to my balance shows no figure of mine`() = runBlocking<Unit> {
        setMembers(me, other, "acct-third")
        itemsRepo.createExpense(listId, "Dinner", dinner())
        // Between the other two: I am on neither side of it.
        itemsRepo.createExpense(listId, "Their deal", payback(from = other, to = "acct-third"))

        showEntries()

        composeTestRule.onNodeWithText("OT → TH").assertIsDisplayed()
        composeTestRule.onNodeWithText("€17.00").assertIsDisplayed()
        // Nothing signed on that row: my own effect is zero, so it says nothing about me — and no
        // bare zero either, which would be a figure claiming to mean something.
        composeTestRule.onAllNodesWithText("+€17.00").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("-€17.00").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("€0.00").assertCountEquals(0)
        // The dinner, which I did pay for, still states mine — on the row and in the card.
        composeTestRule.onAllNodesWithText("+€32.00").assertCountEquals(2)
    }

    // ---- the balances view (T-172: a half of the expense list screen) ----------

    /** Opens the expense list and switches it to balances, the way a user gets there. */
    private fun showBalances(onReimburse: (ExpensePrefill) -> Unit = {}) {
        composeTestRule.setContent {
            ExpenseListScreen(
                onAddExpense = {},
                onEditExpense = {},
                onOpenListProps = {},
                onReimburse = onReimburse,
                viewModel = viewModel(),
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Balances").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `balances name everyone involved and what they paid`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())

        showBalances()

        composeTestRule.onNodeWithText("Net spent: €64.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("$me@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("$other@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("paid 64.00 · share 32.00").assertIsDisplayed()
        // My balance is a credit, so it is signed (T-241); the transfer that settles it (T-165)
        // is a plain amount.
        composeTestRule.onNodeWithText("+€32.00").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("€32.00").assertCountEquals(1)
        composeTestRule.onNodeWithText("-€32.00").assertIsDisplayed()
    }

    @Test
    fun `someone who has left keeps their balance, numbered rather than named`() = runBlocking<Unit> {
        itemsRepo.createExpense(
            listId,
            "Old dinner",
            Expense(mapOf("acct-gone" to "20.00"), true, mapOf(me to "10.00", "acct-gone" to "10.00"), true, "2026-09-16"),
        )

        showBalances()

        // Only an account id remains, so there is no name or email to show.
        composeTestRule.onNodeWithText("Former member 1").assertIsDisplayed()
        // Their balance is a credit, so it is signed (T-241); the transfer that would settle it
        // (T-165) is a plain amount.
        composeTestRule.onNodeWithText("+€10.00").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("€10.00").assertCountEquals(1)
        composeTestRule.onNodeWithText("-€10.00").assertIsDisplayed()
    }

    // ---- settling up (T-165) ---------------------------------------------------

    /** The vote state normally arrives from the server on the list row; seed it directly here. */
    private suspend fun setClosing(closeVotes: List<String> = emptyList(), closedAt: Long? = null) {
        val list = listsRepo.getById(listId)!!
        db.listDao().upsert(list.copy(closeVotesJson = Json.encodeToString(closeVotes), closedAt = closedAt))
    }

    @Test
    fun `settle up says who pays whom, and Record hands over a pre-filled settlement`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())
        var recorded: ExpensePrefill? = null

        showBalances(onReimburse = { recorded = it })

        composeTestRule.onNodeWithText("Settle up").assertIsDisplayed()
        composeTestRule.onNodeWithText("$other@example.com pays $me@example.com").assertIsDisplayed()
        // My balance signed (T-241), the transfer plain.
        composeTestRule.onNodeWithText("+€32.00").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("€32.00").assertCountEquals(1)

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

        composeTestRule.onNodeWithText("Net spent: €12.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("Settle up").assertDoesNotExist()
    }

    // ---- a ledger of all three types, on the balances view (T-245) --------------

    @Test
    fun `balances break the net down and carry what was settled`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())
        itemsRepo.createExpense(listId, "Deposit back", refund())
        itemsRepo.createExpense(listId, "Payback", payback())

        showBalances()

        // 64 laid out less 30 taken in, and the settlement counting for nothing.
        composeTestRule.onNodeWithText("Net spent: €34.00").assertIsDisplayed()
        // The net alone would hide half the story wherever income exists.
        composeTestRule.onNodeWithText("Expenses €64.00 · Income €30.00").assertIsDisplayed()
        // I laid out 64 and took 30 back in; a third of each was mine; and I was paid 17.
        composeTestRule.onNodeWithText("paid 34.00 · share 17.00 · settled -17.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("paid 0.00 · share 17.00 · settled +17.00").assertIsDisplayed()
        // The payback squared us exactly.
        composeTestRule.onNodeWithText("All settled").assertIsDisplayed()
    }

    @Test
    fun `a ledger with no settlements says nothing about settling`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())

        showBalances()

        // Every ledger that has not been paid back yet: the figure would be a zero saying nothing.
        composeTestRule.onNodeWithText("paid 64.00 · share 32.00").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("settled", substring = true).assertCountEquals(0)
        // And with no income, the breakdown line is absent too.
        composeTestRule.onAllNodesWithText("Expenses", substring = true).assertCountEquals(0)
    }

    @Test
    fun `Reimburse hands over a transfer, not an expense`() = runBlocking<Unit> {
        itemsRepo.createExpense(listId, "Dinner", dinner())
        var recorded: ExpensePrefill? = null

        showBalances(onReimburse = { recorded = it })
        composeTestRule.onNodeWithText("Reimburse").performClick()

        val prefill = checkNotNull(recorded)
        // A settlement moves a debt; it is not more spending (T-245).
        assertEquals(ExpenseType.TRANSFER.wire, prefill.expense.type)
        assertEquals(mapOf(other to "32.00"), prefill.expense.paidBy)
        assertEquals(mapOf(me to "32.00"), prefill.expense.paidFor)
    }

    // ---- the entry form's type control (T-245) ---------------------------------

    /** The form, opened on this list with a real view model, as the date-picker test does. */
    private fun showForm(itemId: String? = null) {
        val form = ExpenseFormViewModel(itemsRepo, listsRepo, FakeCurrentAccount().apply { accountId = me })
        composeTestRule.setContent {
            ExpenseDialog(listId = listId, itemId = itemId, onDismiss = {}, viewModel = form)
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the form offers the three types and opens on Expense`() = runBlocking<Unit> {
        showForm()

        composeTestRule.onNodeWithText("Type").assertIsDisplayed()
        composeTestRule.onNodeWithText("Expense").assertIsDisplayed()
        composeTestRule.onNodeWithText("Income").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transfer").assertIsDisplayed()
        // The ordinary case, and the one every entry written before types existed is.
        composeTestRule.onNodeWithText("Paid by").assertIsDisplayed()
        composeTestRule.onNodeWithText("For").assertIsDisplayed()
    }

    @Test
    fun `choosing Income relabels the two sides rather than changing them`() = runBlocking<Unit> {
        showForm()

        composeTestRule.onNodeWithText("Income").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Received by").assertIsDisplayed()
        composeTestRule.onNodeWithText("Credited to").assertIsDisplayed()
        composeTestRule.onNodeWithText("Paid by").assertDoesNotExist()
    }

    @Test
    fun `choosing Transfer replaces the split with two pickers`() = runBlocking<Unit> {
        showForm()

        composeTestRule.onNodeWithText("Transfer").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("From").assertIsDisplayed()
        composeTestRule.onNodeWithText("To").assertIsDisplayed()
        // Me to the first other member, and nothing left to split.
        composeTestRule.onNodeWithText("$me@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("$other@example.com").assertIsDisplayed()
        composeTestRule.onNodeWithText("Paid by").assertDoesNotExist()
    }

    @Test
    fun `a transfer's two ends each leave the other's choice out`() = runBlocking<Unit> {
        setMembers(me, other, "acct-third")
        showForm()
        composeTestRule.onNodeWithText("Transfer").performClick()
        composeTestRule.waitForIdle()

        // From is me, so the To picker offers the other two and not me.
        composeTestRule.onNodeWithText("$other@example.com").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("acct-third@example.com").assertIsDisplayed()
        // Nobody pays themselves: the open menu never offers the sender.
        composeTestRule.onAllNodesWithText("$me@example.com").assertCountEquals(1)
    }

    @Test
    fun `a list of one cannot record a transfer, and says why`() = runBlocking<Unit> {
        setMembers(me)
        showForm()

        composeTestRule.onNodeWithText("Transfer").assertIsNotEnabled()
        composeTestRule.onNodeWithText("A transfer needs two different people on this list.")
            .assertIsDisplayed()
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
                onOpenListProps = {},
                viewModel = model,
            )
        }
        composeTestRule.waitForIdle()

        // An empty list is exactly when someone pulls to see whether anything has arrived.
        composeTestRule.onNodeWithText("No entries yet. Add one to get started.").performTouchInput {
            swipeDown(startY = top, endY = top + 800f, durationMillis = 400)
        }
        composeTestRule.waitForIdle()

        assertEquals(1, syncs)
    }

    // ---- live refresh (T-177) and the date picker (T-185) ------------------------

    @Test
    fun `an open expense list re-syncs every five seconds, as a shopping list does`() = runTest {
        var syncs = 0
        val model = viewModel(syncer = Syncer { syncs++; SyncResult.Success(0, 0, 0, 0) })

        val loop = launch { model.liveSyncLoop() }
        advanceTimeBy(4_999)
        assertEquals(0, syncs)
        advanceTimeBy(2)
        assertEquals(1, syncs)
        advanceTimeBy(5_000)
        assertEquals(2, syncs)
        loop.cancel()
    }

    @Test
    fun `the expense date is picked from a calendar, not typed`() = runBlocking<Unit> {
        val form = ExpenseFormViewModel(itemsRepo, listsRepo, FakeCurrentAccount().apply { accountId = me })
        composeTestRule.setContent { ExpenseDialog(listId = listId, itemId = null, onDismiss = {}, viewModel = form) }
        composeTestRule.waitForIdle()
        val today = form.uiState.value.date

        composeTestRule.onNodeWithContentDescription("Date").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("OK").assertIsDisplayed()

        // Confirming without choosing another day keeps the one the form started with.
        composeTestRule.onNodeWithText("OK").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("OK").assertDoesNotExist()
        assertEquals(today, form.uiState.value.date)
    }
}
