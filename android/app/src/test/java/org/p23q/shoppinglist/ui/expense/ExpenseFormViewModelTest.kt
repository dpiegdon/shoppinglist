package org.p23q.shoppinglist.ui.expense

import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.testListAccounts
import kotlinx.coroutines.runBlocking
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.ExpenseMath
import org.p23q.shoppinglist.core.ExpenseType
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.ListMember
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.robolectric.RobolectricTestRunner

/**
 * The expense form (T-154). The arithmetic itself is covered by ExpenseMathTest against the
 * cross-client case table; this covers what the form does with it — defaults, what a typed amount
 * protects, and what reaches the database on save.
 */
@RunWith(RobolectricTestRunner::class)
class ExpenseFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDb
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listsRepo: ListsRepo
    private lateinit var listId: String

    private val me = "acct-me"
    private val other = "acct-other"

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
        setMembers(me, other)
    }

    /** The roster normally arrives from the server on the list row; seed it directly here. */
    private suspend fun setMembers(vararg ids: String) {
        // Initials from the part after the dash: "acct-me" and "acct-other" both start "AC".
        val members = ids.map {
            ListMember(it, "$it@example.com", it.substringAfter('-').take(2).uppercase())
        }
        val list = listsRepo.getById(listId)!!
        db.listDao().upsert(list.copy(membersJson = Json.encodeToString(members)))
    }

    private fun newViewModel() = ExpenseFormViewModel(itemsRepo, listsRepo, testListAccounts(db, listsRepo))

    private suspend fun storedExpense(): Expense =
        itemsRepo.decodeExpense(itemsRepo.activeItemsForListOnce(listId).first().expense.value)!!

    // ---- adding ---------------------------------------------------------------

    @Test
    fun `a new expense defaults to paid by me, for everyone`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()

        val state = viewModel.uiState.value
        assertEquals(listOf(true, false), state.paidBy.map { it.selected })
        assertEquals(listOf(true, true), state.paidFor.map { it.selected })
        assertEquals("EUR", state.currency)
        // Today, not empty: a date the user has to fill in every time is a date they will get wrong.
        assertTrue(state.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `on another account's list, "me" is that account (T-292)`() = runTest(mainDispatcherRule.dispatcher) {
        // The other member signed in on this phone too, as a second account holding its own row of the list.
        db.insertTestAccount(testAccount(id = "second", accountId = other, serverUrl = "https://other.example.test/"))
        val theirs = listsRepo.create("second", "Trip", ListKind.EXPENSES, currency = "EUR")
        val members = listOf(me, other).map { ListMember(it, "$it@example.com", it.substringAfter('-').take(2).uppercase()) }
        db.listDao().upsert(listsRepo.getById(theirs)!!.copy(membersJson = Json.encodeToString(members)))

        val viewModel = newViewModel()
        viewModel.startAdd(theirs).join()

        assertEquals(listOf(false, true), viewModel.uiState.value.paidBy.map { it.selected })
    }

    @Test
    fun `an equal split is written with the flags that let it redistribute later`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()
            viewModel.onNameChange("Dinner")
            viewModel.onTotalChange("64.01")

            viewModel.save()?.join()

            val expense = storedExpense()
            assertEquals(mapOf(me to "64.01"), expense.paidBy)
            // The odd cent goes to the first participant, and both maps still sum to the total.
            assertEquals(mapOf(me to "32.01", other to "32.00"), expense.paidFor)
            assertTrue(expense.equalBy)
            assertTrue(expense.equalFor)
        }

    @Test
    fun `a typed share stays put while the rest absorb a changed total`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()
            viewModel.onNameChange("Lobster")
            viewModel.onTotalChange("60.00")
            viewModel.onShareChange(Side.PAID_FOR, me, "30.00")

            viewModel.onTotalChange("64.00")

            val forRows = viewModel.uiState.value.paidFor.associateBy { it.accountId }
            assertEquals("30.00", forRows.getValue(me).text)
            assertEquals(3400L, forRows.getValue(other).derivedCents)

            viewModel.save()?.join()
            val expense = storedExpense()
            assertEquals(mapOf(me to "30.00", other to "34.00"), expense.paidFor)
            // No longer an equal split, so reopening it must not redistribute.
            assertFalse(expense.equalFor)
        }

    @Test
    fun `typed amounts that miss the total block the save and offer to become it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()
            viewModel.onNameChange("Groceries")
            viewModel.onTotalChange("60.00")
            viewModel.onShareChange(Side.PAID_FOR, me, "20.00")
            viewModel.onShareChange(Side.PAID_FOR, other, "20.00")

            assertEquals(ExpenseMath.DistributeError.FIXED_SUM_MISMATCH, viewModel.uiState.value.paidForError)
            assertFalse(viewModel.uiState.value.canSave)
            assertNull(viewModel.save())

            viewModel.useSumAsTotal(Side.PAID_FOR)
            assertEquals("40.00", viewModel.uiState.value.totalText)
            assertNull(viewModel.uiState.value.paidForError)
            assertTrue(viewModel.uiState.value.canSave)
        }

    @Test
    fun `typed amounts above the total block the save`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()
        viewModel.onNameChange("Too much")
        viewModel.onTotalChange("10.00")
        viewModel.onShareChange(Side.PAID_FOR, me, "99.00")

        assertEquals(ExpenseMath.DistributeError.FIXED_EXCEEDS_TOTAL, viewModel.uiState.value.paidForError)
        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun `a blank name blocks the save and is reported`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()
        viewModel.onTotalChange("10.00")

        assertNull(viewModel.save())
        assertTrue(viewModel.uiState.value.nameError)
    }

    @Test
    fun `deselecting someone drops their typed amount rather than keeping it stale`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()
            viewModel.onNameChange("Taxi")
            viewModel.onTotalChange("30.00")
            viewModel.onShareChange(Side.PAID_FOR, other, "10.00")

            viewModel.toggleParticipant(Side.PAID_FOR, other)
            viewModel.toggleParticipant(Side.PAID_FOR, other)

            val row = viewModel.uiState.value.paidFor.first { it.accountId == other }
            assertEquals("", row.text)
            assertEquals(1500L, row.derivedCents)
        }

    // ---- a list of one --------------------------------------------------------

    @Test
    fun `a solo list has nothing to distribute and books the whole amount`() =
        runTest(mainDispatcherRule.dispatcher) {
            setMembers(me)
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()
            viewModel.onNameChange("Coffee")
            viewModel.onTotalChange("4.20")

            assertTrue(viewModel.uiState.value.soloList)
            viewModel.save()?.join()

            val expense = storedExpense()
            assertEquals(mapOf(me to "4.20"), expense.paidBy)
            assertEquals(mapOf(me to "4.20"), expense.paidFor)
        }

    // ---- editing --------------------------------------------------------------

    @Test
    fun `reopening an equal split redistributes on a corrected total`() =
        runTest(mainDispatcherRule.dispatcher) {
            val itemId = itemsRepo.createExpense(
                listId,
                "Dinner",
                Expense(mapOf(me to "64.00"), true, mapOf(me to "32.00", other to "32.00"), true, "2026-09-17"),
            )
            val viewModel = newViewModel()
            viewModel.startEdit(itemId).join()
            assertEquals("64.00", viewModel.uiState.value.totalText)

            viewModel.onTotalChange("70.00")
            viewModel.save()?.join()

            val expense = storedExpense()
            assertEquals(mapOf(me to "70.00"), expense.paidBy)
            assertEquals(mapOf(me to "35.00", other to "35.00"), expense.paidFor)
        }

    @Test
    fun `reopening typed amounts keeps them, and refuses a total they no longer match`() =
        runTest(mainDispatcherRule.dispatcher) {
            val itemId = itemsRepo.createExpense(
                listId,
                "Split bill",
                Expense(mapOf(me to "40.00", other to "24.00"), false, mapOf(me to "32.00", other to "32.00"), true, "2026-09-17"),
            )
            val viewModel = newViewModel()
            viewModel.startEdit(itemId).join()

            assertEquals(listOf("40.00", "24.00"), viewModel.uiState.value.paidBy.map { it.text })
            viewModel.onTotalChange("70.00")
            assertEquals(ExpenseMath.DistributeError.FIXED_SUM_MISMATCH, viewModel.uiState.value.paidByError)
        }

    @Test
    fun `someone who has left stays on the expense and stays editable`() =
        runTest(mainDispatcherRule.dispatcher) {
            val gone = "acct-gone"
            val itemId = itemsRepo.createExpense(
                listId,
                "Old dinner",
                Expense(mapOf(gone to "20.00"), true, mapOf(me to "10.00", gone to "10.00"), true, "2026-09-16"),
            )
            val viewModel = newViewModel()
            viewModel.startEdit(itemId).join()

            val payer = viewModel.uiState.value.paidBy.first { it.accountId == gone }
            assertTrue(payer.selected)
            // No email to show, so the screen labels them by number instead.
            assertNull(payer.email)
            assertEquals(1, payer.formerNumber)
        }

    @Test
    fun `former members are numbered across the list, not within the one expense`() =
        runTest(mainDispatcherRule.dispatcher) {
            val goneA = "acct-gone-a"
            val goneB = "acct-gone-b"
            // Numbered by first appearance newest-first, which is the order the list shows: the
            // taxi's departed participant is 1 and the museum's is 2, on every screen (T-197).
            itemsRepo.createExpense(
                listId,
                "Taxi",
                Expense(mapOf(me to "20.00"), true, mapOf(me to "10.00", goneA to "10.00"), true, "2026-09-17"),
            )
            val museum = itemsRepo.createExpense(
                listId,
                "Museum",
                Expense(mapOf(me to "30.00"), true, mapOf(me to "15.00", goneB to "15.00"), true, "2026-09-16"),
            )
            val viewModel = newViewModel()
            viewModel.startEdit(museum).join()

            assertEquals(2, viewModel.uiState.value.paidFor.first { it.accountId == goneB }.formerNumber)
        }

    @Test
    fun `a backlogged entry is excluded from numbering, matching the ledger (T-265)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val goneNewer = "acct-gone-newer"
            val goneOlder = "acct-gone-older"
            // The newer entry is backlog (T-265's finding: the ledger's itemsForList excludes it,
            // but the form used activeItemsForListOnce, which does not — so the two could number
            // the same former member differently). Backlogged, its participant must not take
            // number 1 by sorting first; the surviving, visible entry should.
            val backlogged = itemsRepo.createExpense(
                listId,
                "Refunded taxi",
                Expense(mapOf(me to "20.00"), true, mapOf(me to "10.00", goneNewer to "10.00"), true, "2026-09-20"),
            )
            itemsRepo.setStatus(backlogged, Status.BACKLOG)
            val museum = itemsRepo.createExpense(
                listId,
                "Museum",
                Expense(mapOf(me to "30.00"), true, mapOf(me to "15.00", goneOlder to "15.00"), true, "2026-09-16"),
            )
            val viewModel = newViewModel()
            viewModel.startEdit(museum).join()

            assertEquals(1, viewModel.uiState.value.paidFor.first { it.accountId == goneOlder }.formerNumber)
        }

    @Test
    fun `an untouched edit writes nothing at all`() = runTest(mainDispatcherRule.dispatcher) {
        val expense = Expense(mapOf(me to "64.00"), true, mapOf(me to "32.00", other to "32.00"), true, "2026-09-17")
        val itemId = itemsRepo.createExpense(listId, "Dinner", expense)
        itemsRepo.clearDirty(itemsRepo.dirtyRows().map { it.localId })
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.save()?.join()

        // Change-scoped (T-88): nothing changed, so no clock is re-stamped and nothing is pushed.
        assertFalse(itemsRepo.getById(itemId)!!.dirty)
    }

    @Test
    fun `deleting tombstones the expense`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createExpense(
            listId,
            "Mistake",
            Expense(mapOf(me to "5.00"), true, mapOf(me to "5.00"), true, "2026-09-17"),
        )
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.requestDelete()
        assertTrue(viewModel.uiState.value.isDeleteConfirmOpen)
        viewModel.confirmDelete()?.join()

        assertTrue(itemsRepo.getById(itemId)!!.deleted.value)
        assertTrue(viewModel.uiState.value.isDeleted)
    }

    /** The vote state normally arrives from the server on the list row. */
    private suspend fun setCloseVotes(vararg ids: String) {
        val list = listsRepo.getById(listId)!!
        db.listDao().upsert(list.copy(closeVotesJson = Json.encodeToString(ids.toList())))
    }

    /** One 60.00 dinner, split evenly, as the fixture for the freeze cases below. */
    private suspend fun expenseId(): String = itemsRepo.createExpense(
        listId,
        "Dinner",
        Expense(mapOf(me to "60.00"), true, mapOf(me to "30.00", other to "30.00"), true, "2026-09-17"),
    )

    // ---- the freeze (T-158) ----------------------------------------------------

    @Test
    fun `a voter's row is locked, shown rather than hidden`() = runTest(mainDispatcherRule.dispatcher) {
        setCloseVotes(other)
        val itemId = expenseId()
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        val theirs = viewModel.uiState.value.paidFor.first { it.accountId == other }
        val mine = viewModel.uiState.value.paidFor.first { it.accountId == me }
        // Frozen, and the row says which of the two put the lock there (T-203).
        assertEquals(FrozenReason.VOTER, theirs.frozen)
        assertTrue(theirs.selected)
        // The freeze is about their money; everyone else is still editable.
        assertEquals(FrozenReason.NONE, mine.frozen)
    }

    @Test
    fun `a frozen participant cannot be deselected or retyped`() = runTest(mainDispatcherRule.dispatcher) {
        setCloseVotes(other)
        val itemId = expenseId()
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.toggleParticipant(Side.PAID_FOR, other)
        viewModel.onShareChange(Side.PAID_FOR, other, "999")

        val theirs = viewModel.uiState.value.paidFor.first { it.accountId == other }
        assertTrue(theirs.selected)
        // Still showing what they actually owe, not what was typed at them: the amount is part of
        // the record, so the row displays it and refuses the edit rather than blanking.
        assertEquals("30.00", theirs.text)
    }

    @Test
    fun `a frozen share never absorbs a change made elsewhere`() = runTest(mainDispatcherRule.dispatcher) {
        setCloseVotes(other)
        val itemId = expenseId()
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        viewModel.onTotalChange("80.00")

        // Their 30 stays exactly as it was; the rest of the change lands on the unfrozen share.
        val theirs = viewModel.uiState.value.paidFor.first { it.accountId == other }
        val mine = viewModel.uiState.value.paidFor.first { it.accountId == me }
        assertEquals(3000L, theirs.derivedCents)
        assertEquals(5000L, mine.derivedCents)
    }

    @Test
    fun `a new expense never starts out involving a voter`() = runTest(mainDispatcherRule.dispatcher) {
        setCloseVotes(other)
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()

        val theirs = viewModel.uiState.value.paidFor.first { it.accountId == other }
        assertFalse(theirs.selected)
        assertTrue(viewModel.uiState.value.paidFor.first { it.accountId == me }.selected)
    }

    @Test
    fun `an expense involving someone frozen cannot be deleted (T-193)`() = runTest(mainDispatcherRule.dispatcher) {
        setCloseVotes(other)
        val itemId = expenseId()
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        // Deleting would take their 30 to zero, which the freeze forbids.
        assertFalse(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `an expense involving only unfrozen people can be deleted`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = expenseId()
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        assertTrue(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `an expense naming someone who has left cannot be deleted either`() = runTest(mainDispatcherRule.dispatcher) {
        setMembers(me)
        val itemId = expenseId()
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        assertFalse(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `someone who has left is frozen without having voted`() = runTest(mainDispatcherRule.dispatcher) {
        // The roster no longer names them, but the expense still does.
        setMembers(me)
        val itemId = expenseId()
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        // Frozen for having left, not for a vote they never cast (T-203).
        assertEquals(
            FrozenReason.FORMER,
            viewModel.uiState.value.paidFor.first { it.accountId == other }.frozen,
        )
    }

    // ---- a pre-filled settlement (T-165, now a transfer: T-245) -----------------

    private fun settlement(amount: String = "22.00") = ExpensePrefill(
        name = "Settlement",
        expense = Expense(
            mapOf(other to amount),
            true,
            mapOf(me to amount),
            true,
            "2026-09-18",
            type = ExpenseType.TRANSFER.wire,
        ),
    )

    @Test
    fun `a prefilled settlement opens as a transfer with its name, total, payer and payee`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId, settlement()).join()

            val state = viewModel.uiState.value
            assertFalse(state.isEditMode)
            assertEquals("Settlement", state.name)
            assertEquals("22.00", state.totalText)
            assertEquals("2026-09-18", state.date)
            assertEquals(ExpenseType.TRANSFER, state.type)
            assertEquals(other, state.transferFrom)
            assertEquals(me, state.transferTo)
            assertTrue(state.canSave)
        }

    @Test
    fun `saving a prefilled settlement writes a transfer`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId, settlement()).join()
            viewModel.save("Transfer")!!.join()

            assertEquals("Settlement", itemsRepo.activeItemsForListOnce(listId).first().name.value)
            val stored = storedExpense()
            assertEquals(ExpenseType.TRANSFER.wire, stored.type)
            assertEquals(mapOf(other to "22.00"), stored.paidBy)
            assertEquals(mapOf(me to "22.00"), stored.paidFor)
        }

    @Test
    fun `a partial settlement is a changed total`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId, settlement()).join()
        viewModel.onTotalChange("10.00")
        viewModel.save("Transfer")!!.join()

        val stored = storedExpense()
        assertEquals(mapOf(other to "10.00"), stored.paidBy)
        assertEquals(mapOf(me to "10.00"), stored.paidFor)
    }

    // ---- the three entry types (T-245) -----------------------------------------

    @Test
    fun `a new entry is an expense, and says so on the wire`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()
        assertEquals(ExpenseType.EXPENSE, viewModel.uiState.value.type)

        viewModel.onNameChange("Dinner")
        viewModel.onTotalChange("60.00")
        viewModel.save("Expense")?.join()

        assertEquals(ExpenseType.EXPENSE.wire, storedExpense().type)
    }

    @Test
    fun `an income keeps the same two maps and only changes what they mean`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()
            viewModel.onNameChange("Deposit back")
            viewModel.onTotalChange("30.00")

            viewModel.onTypeChange(ExpenseType.INCOME)

            // Expense and Income are the same form read two ways, so nothing about the split moves.
            assertEquals(listOf(true, false), viewModel.uiState.value.paidBy.map { it.selected })
            assertEquals(listOf(true, true), viewModel.uiState.value.paidFor.map { it.selected })
            viewModel.save("Income")?.join()

            val stored = storedExpense()
            assertEquals(ExpenseType.INCOME.wire, stored.type)
            assertEquals(mapOf(me to "30.00"), stored.paidBy)
            assertEquals(mapOf(me to "15.00", other to "15.00"), stored.paidFor)
        }

    @Test
    fun `switching to a transfer keeps the title, date, note and total`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()
            viewModel.onNameChange("Payback")
            viewModel.onNoteChange("cash")
            viewModel.onDateChange("2026-09-18")
            viewModel.onTotalChange("25.00")

            viewModel.onTypeChange(ExpenseType.TRANSFER)

            val state = viewModel.uiState.value
            assertEquals("Payback", state.name)
            assertEquals("cash", state.note)
            assertEquals("2026-09-18", state.date)
            assertEquals("25.00", state.totalText)
        }

    @Test
    fun `switching to a transfer starts from me and the first other member`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()

            // The default maps are me paying for everyone, which is not one sender and one
            // recipient, so the two ends are chosen rather than translated.
            viewModel.onTypeChange(ExpenseType.TRANSFER)

            assertEquals(me, viewModel.uiState.value.transferFrom)
            assertEquals(other, viewModel.uiState.value.transferTo)
        }

    @Test
    fun `switching to a transfer keeps a split that already named one of each`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()
            viewModel.onNameChange("Payback")
            viewModel.onTotalChange("25.00")
            // The other paid, and it was for me alone: exactly a transfer, said the long way.
            viewModel.toggleParticipant(Side.PAID_BY, me)
            viewModel.toggleParticipant(Side.PAID_BY, other)
            viewModel.toggleParticipant(Side.PAID_FOR, other)

            viewModel.onTypeChange(ExpenseType.TRANSFER)

            assertEquals(other, viewModel.uiState.value.transferFrom)
            assertEquals(me, viewModel.uiState.value.transferTo)
        }

    @Test
    fun `switching away from a transfer makes the sender the payer and the recipient the share`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId, settlement()).join()

            viewModel.onTypeChange(ExpenseType.EXPENSE)

            // The other sent it, so they are the sole payer; it was for me, so I am the sole share.
            assertEquals(listOf(false, true), viewModel.uiState.value.paidBy.map { it.selected })
            assertEquals(listOf(true, false), viewModel.uiState.value.paidFor.map { it.selected })
            viewModel.save("Expense")?.join()

            val stored = storedExpense()
            assertEquals(ExpenseType.EXPENSE.wire, stored.type)
            assertEquals(mapOf(other to "22.00"), stored.paidBy)
            assertEquals(mapOf(me to "22.00"), stored.paidFor)
            assertTrue(stored.equalBy)
            assertTrue(stored.equalFor)
        }

    @Test
    fun `a transfer writes one sender, one recipient and nothing else`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()
            viewModel.onNameChange("Payback")
            viewModel.onTotalChange("25.00")
            viewModel.onTypeChange(ExpenseType.TRANSFER)

            viewModel.save("Transfer")?.join()

            val stored = storedExpense()
            assertEquals(ExpenseType.TRANSFER.wire, stored.type)
            assertEquals(mapOf(me to "25.00"), stored.paidBy)
            assertEquals(mapOf(other to "25.00"), stored.paidFor)
            assertTrue(stored.equalBy)
            assertTrue(stored.equalFor)
        }

    @Test
    fun `a transfer cannot point both ends at the same person`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()
        viewModel.onNameChange("Payback")
        viewModel.onTotalChange("25.00")
        viewModel.onTypeChange(ExpenseType.TRANSFER)

        viewModel.onTransferToChange(me)

        assertTrue(viewModel.uiState.value.sameMemberError)
        assertFalse(viewModel.uiState.value.canSave)
        assertNull(viewModel.save("Transfer"))
    }

    @Test
    fun `a transfer without a total cannot be saved`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()
        viewModel.onTypeChange(ExpenseType.TRANSFER)

        assertFalse(viewModel.uiState.value.canSave)
        assertNull(viewModel.save("Transfer"))
    }

    @Test
    fun `an untitled income or transfer names itself, but an expense still insists`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()
            viewModel.onTotalChange("30.00")

            // An expense with no title is still refused, and says so.
            assertFalse(viewModel.uiState.value.canSave)
            assertNull(viewModel.save("Expense"))
            assertTrue(viewModel.uiState.value.nameError)

            viewModel.onTypeChange(ExpenseType.INCOME)
            assertTrue(viewModel.uiState.value.canSave)
            viewModel.save("Income")?.join()

            assertEquals("Income", itemsRepo.activeItemsForListOnce(listId).first().name.value)
        }

    @Test
    fun `a list of one cannot record a transfer`() = runTest(mainDispatcherRule.dispatcher) {
        setMembers(me)
        val viewModel = newViewModel()
        viewModel.startAdd(listId).join()

        // Nobody to pay, so the segment is off and asking for it changes nothing.
        assertFalse(viewModel.uiState.value.canTransfer)
    }

    @Test
    fun `a transfer may not name someone whose amounts are frozen`() =
        runTest(mainDispatcherRule.dispatcher) {
            setCloseVotes(other)
            val viewModel = newViewModel()
            viewModel.startAdd(listId).join()

            // Only I can move, and a transfer needs two who can.
            assertFalse(viewModel.uiState.value.canTransfer)
        }

    @Test
    fun `an entry stored before types existed opens as an expense and can become a transfer`() =
        runTest(mainDispatcherRule.dispatcher) {
            // No type at all — every entry written before T-245 looks like this.
            val itemId = itemsRepo.createExpense(
                listId,
                "Settlement",
                Expense(mapOf(other to "20.00"), true, mapOf(me to "20.00"), true, "2026-09-17"),
            )
            val viewModel = newViewModel()
            viewModel.startEdit(itemId).join()
            assertEquals(ExpenseType.EXPENSE, viewModel.uiState.value.type)

            viewModel.onTypeChange(ExpenseType.TRANSFER)
            // One payer and one beneficiary already, so the ends are translated, not chosen.
            assertEquals(other, viewModel.uiState.value.transferFrom)
            assertEquals(me, viewModel.uiState.value.transferTo)
            viewModel.save("Transfer")?.join()

            val stored = storedExpense()
            assertEquals(ExpenseType.TRANSFER.wire, stored.type)
            assertEquals(mapOf(other to "20.00"), stored.paidBy)
            assertEquals(mapOf(me to "20.00"), stored.paidFor)
        }

    @Test
    fun `reopening a stored transfer shows its two ends`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createExpense(
            listId,
            "Settlement",
            Expense(
                mapOf(other to "20.00"),
                true,
                mapOf(me to "20.00"),
                true,
                "2026-09-17",
                type = ExpenseType.TRANSFER.wire,
            ),
        )
        val viewModel = newViewModel()
        viewModel.startEdit(itemId).join()

        val state = viewModel.uiState.value
        assertEquals(ExpenseType.TRANSFER, state.type)
        assertEquals(other, state.transferFrom)
        assertEquals(me, state.transferTo)
        assertEquals("20.00", state.totalText)
    }
}
