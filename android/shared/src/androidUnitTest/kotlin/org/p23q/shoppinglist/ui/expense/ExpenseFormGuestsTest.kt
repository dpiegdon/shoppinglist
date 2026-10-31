package org.p23q.shoppinglist.ui.expense

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.ExpenseType
import org.p23q.shoppinglist.core.ListGuest
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.ListMember
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.testListAccounts
import org.robolectric.RobolectricTestRunner

/**
 * The entry form with a ledger's guests (T-364): who it offers, in which order, and what a locked
 * guest's amounts do. Labels and the arithmetic are covered by ExpenseMathTest and the screen tests.
 */
@RunWith(RobolectricTestRunner::class)
class ExpenseFormGuestsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDb
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listsRepo: ListsRepo
    private lateinit var listId: String

    private val me = "acct-me"
    private val other = "acct-other"
    private val dave = ListGuest("g:dave", "Dave", "DA")
    private val eve = ListGuest("g:eve", "Eve", "EV", locked = true)
    private val carl = ListGuest("g:carl", "Carl", "CA", claimedBy = "acct-other")

    @Before
    fun setUp() = runTest(mainDispatcherRule.dispatcher) {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
            .build()
        db.insertTestAccount()
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        listId = listsRepo.create(TEST_ACCOUNT_ID, "Trip", ListKind.EXPENSES, currency = "EUR")
        setRoster(listOf(dave, eve, carl))
    }

    private suspend fun setRoster(guests: List<ListGuest>) {
        val members = listOf(me, other).map { ListMember(it, "$it@example.com", it.substringAfter('-').take(2).uppercase()) }
        val list = listsRepo.getById(listId)!!
        db.listDao().upsert(list.copy(membersJson = Json.encodeToString(members), guestsJson = Json.encodeToString(guests)))
    }

    private fun newViewModel() = ExpenseFormViewModel(itemsRepo, listsRepo, testListAccounts(db, listsRepo))

    @Test
    fun `members then unclaimed guests are offered, a claimed guest never`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()

        val state = viewModel.uiState.value
        assertEquals(listOf(me, other, dave.id, eve.id), state.paidFor.map { it.accountId })
        assertEquals(ParticipantName.Guest("Dave", "DA"), state.paidFor[2].name)
    }

    @Test
    fun `a new entry is for everyone but a locked guest, who is frozen as a voter is`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()

        val rows = viewModel.uiState.value.paidFor.associateBy { it.accountId }
        assertTrue(rows.getValue(dave.id).selected)
        assertFalse(rows.getValue(eve.id).selected)
        assertEquals(FrozenReason.LOCKED, rows.getValue(eve.id).frozen)
        assertEquals(FrozenReason.NONE, rows.getValue(dave.id).frozen)

        // A frozen participant cannot be ticked.
        viewModel.toggleParticipant(Side.PAID_FOR, eve.id)
        assertFalse(viewModel.uiState.value.paidFor.first { it.accountId == eve.id }.selected)
    }

    @Test
    fun `editing an equal split keeps a locked guest's share where it is (T-196)`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createExpense(
            listId,
            "Dinner",
            Expense(mapOf(me to "30.00"), true, mapOf(me to "10.00", dave.id to "10.00", eve.id to "10.00"), true, "2026-10-31"),
        )
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.onTotalChange("60.00")
        viewModel.save()!!.join()

        val stored = itemsRepo.decodeExpense(itemsRepo.getById(itemId)!!.expense.value)!!
        // Eve's 10.00 stays; the other two absorb the change.
        assertEquals(mapOf(me to "25.00", dave.id to "25.00", eve.id to "10.00"), stored.paidFor)
    }

    @Test
    fun `a stored entry naming a claimed guest keeps it, labelled by name and claimant, frozen by the claimant's reason`() =
        runTest(mainDispatcherRule.dispatcher) {
            val itemId = itemsRepo.createExpense(
                listId,
                "Taxi",
                Expense(mapOf(carl.id to "20.00"), true, mapOf(me to "20.00"), true, "2026-10-31"),
            )
            val listRow = listsRepo.getById(listId)!!
            db.listDao().upsert(listRow.copy(closeVotesJson = Json.encodeToString(listOf(other))))
            val viewModel = newViewModel()
            viewModel.startEdit(itemId).join()

            val carlRow = viewModel.uiState.value.paidBy.first { it.accountId == carl.id }
            assertEquals(ParticipantName.ClaimedGuest("Carl", ParticipantName.Member("acct-other@example.com")), carlRow.name)
            // The claimant has voted: the guest's numbers are theirs, so they are frozen as a voter.
            assertEquals(FrozenReason.VOTER, carlRow.frozen)
        }

    @Test
    fun `a transfer may name a guest on either side`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()
        viewModel.onTypeChange(ExpenseType.TRANSFER)
        viewModel.onTransferFromChange(dave.id)
        viewModel.onTransferToChange(me)
        viewModel.onNameChange("Payback")
        viewModel.onTotalChange("5.00")
        viewModel.save()!!.join()

        val stored = itemsRepo.decodeExpense(itemsRepo.activeItemsForListOnce(listId).first().expense.value)!!
        assertEquals(mapOf(dave.id to "5.00"), stored.paidBy)
        assertEquals(mapOf(me to "5.00"), stored.paidFor)
        // A locked guest is no transfer candidate.
        assertTrue(viewModel.uiState.value.participants.first { it.accountId == eve.id }.isFrozen)
    }
}
