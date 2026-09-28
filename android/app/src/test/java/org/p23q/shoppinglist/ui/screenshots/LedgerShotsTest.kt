package org.p23q.shoppinglist.ui.screenshots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.ExpenseType
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.expense.ExpenseListScreen
import org.p23q.shoppinglist.ui.expense.ExpenseListViewModel
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An expenses list: the ledger with its entries, and the balances view (T-325). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class LedgerShotsTest(theme: ShotTheme) : ScreenshotTest(theme) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = ShotTheme.both()
    }

    private val sam = "acct-sam"
    private val alex = "acct-alex"
    private val robin = "acct-robin"

    private fun split(paidBy: String, total: String, vararg forWhom: Pair<String, String>, date: String, type: ExpenseType? = null) = Expense(
        paidBy = mapOf(paidBy to total),
        equalBy = true,
        paidFor = forWhom.toMap(),
        equalFor = forWhom.map { it.second }.distinct().size == 1,
        date = date,
        type = type?.wire,
    )

    private fun show(balances: Boolean) = runBlocking<Unit> {
        serverAnswer = { _, path -> if (path.endsWith("/members")) rosterJson() else null }
        accounts.add("https://lists.example.org/", id = "home", accountId = sam, email = "sam@example.org")
        val list = listsRepo.create("home", "Trip to Lisbon", ListKind.EXPENSES, currency = "EUR")
        setMembers(list, *ROSTER.toTypedArray())
        itemsRepo.createExpense(list, "Flat for three nights", split(sam, "360.00", sam to "120.00", alex to "120.00", robin to "120.00", date = "2026-09-12"))
        itemsRepo.createExpense(list, "Dinner at the harbour", split(alex, "96.00", sam to "32.00", alex to "32.00", robin to "32.00", date = "2026-09-13"), note = "Tip included")
        itemsRepo.createExpense(list, "Tram tickets", split(robin, "25.50", sam to "12.75", robin to "12.75", date = "2026-09-14"))
        itemsRepo.createExpense(list, "Deposit returned", split(sam, "60.00", sam to "20.00", alex to "20.00", robin to "20.00", date = "2026-09-15", type = ExpenseType.INCOME))
        itemsRepo.createExpense(list, "Alex pays Sam back", split(alex, "50.00", sam to "50.00", date = "2026-09-16", type = ExpenseType.TRANSFER))
        val viewModel = ExpenseListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to list)),
            itemsRepo,
            listsRepo,
            accounts.listAccounts(listsRepo),
            syncer,
        ).tracked()

        setScreen("Trip to Lisbon") {
            ExpenseListScreen(onAddExpense = {}, onEditExpense = {}, onOpenListProps = {}, viewModel = viewModel)
        }
        awaitUntil { viewModel.uiState.value.rows.isNotEmpty() }
        if (balances) {
            composeTestRule.onNodeWithText(str(R.string.expense_balances)).performClick()
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun ledger() {
        show(balances = false)
        capture("ledger")
    }

    @Test
    fun balances() {
        show(balances = true)
        capture("ledger-balances")
    }
}
