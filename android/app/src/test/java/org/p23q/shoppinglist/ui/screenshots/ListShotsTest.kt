package org.p23q.shoppinglist.ui.screenshots

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.data.ShowCheckedStore
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.item.EditItemDialog
import org.p23q.shoppinglist.ui.item.ItemFormViewModel
import org.p23q.shoppinglist.ui.list.ListScreen
import org.p23q.shoppinglist.ui.list.ListViewModel
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A shopping list, a checklist with due dates, and the item dialog, light and dark (T-325). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ListShotsTest(theme: ShotTheme) : ScreenshotTest(theme) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = ShotTheme.both()
    }

    private suspend fun account() {
        serverAnswer = { _, path ->
            if (path.endsWith("/members")) {
                """
                {"members": [
                  {"account_id": "acct-sam", "email": "sam@example.org", "initials": "SA", "joined_at": 1},
                  {"account_id": "acct-alex", "email": "alex@example.org", "initials": "AL", "joined_at": 2}
                ], "invites": []}
                """.trimIndent()
            } else {
                null
            }
        }
        accounts.add("https://lists.example.org/", id = "home", accountId = "acct-sam", email = "sam@example.org")
    }

    private suspend fun item(
        listId: String,
        name: String,
        category: String? = null,
        quantity: String? = null,
        stores: List<String> = emptyList(),
        price: String? = null,
        note: String? = null,
        due: String? = null,
        checked: Boolean = false,
    ): String {
        val id = itemsRepo.createItem(listId, name, if (checked) Status.CHECKED else Status.TODO)
        if (category != null) itemsRepo.setCategory(id, category)
        if (quantity != null) itemsRepo.setQuantity(id, quantity)
        if (stores.isNotEmpty()) itemsRepo.setStores(id, stores)
        if (price != null) itemsRepo.setPrice(id, price, "EUR")
        if (note != null) itemsRepo.setNote(id, note)
        if (due != null) itemsRepo.setDue(id, due)
        return id
    }

    /** The groceries list; returns its id and the id of the item the dialog shot edits. */
    private suspend fun groceries(): Pair<String, String> {
        val list = listsRepo.create("home", "Groceries")
        listsRepo.setCategoryOrder(list, listOf("Produce", "Dairy", "Bakery"))
        item(list, "Apples", category = "Produce", quantity = "1 kg", stores = listOf("Market"))
        item(list, "Tomatoes", category = "Produce", quantity = "6")
        val milk = item(list, "Milk", category = "Dairy", quantity = "2 l", stores = listOf("Corner shop", "Market"), price = "1.29", note = "The organic one")
        item(list, "Butter", category = "Dairy", checked = true)
        item(list, "Sourdough loaf", category = "Bakery", stores = listOf("Bakery Rosa"))
        item(list, "Dish soap")
        item(list, "Coffee beans", quantity = "500 g", checked = true)
        return list to milk
    }

    private fun listViewModel(listId: String): ListViewModel = ListViewModel(
        SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
        itemsRepo,
        listsRepo,
        syncer,
        accounts.syncStatus,
        accounts.listAccounts(listsRepo),
        prefs("show_checked") { ShowCheckedStore(it) },
    ).tracked()

    private fun showList(title: String, viewModel: ListViewModel, dialog: (@androidx.compose.runtime.Composable () -> Unit)? = null) {
        setScreen(title) {
            ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel, currentDate = { SCREENSHOT_TODAY })
            dialog?.invoke()
        }
        awaitUntil { viewModel.uiState.value.listName.isNotEmpty() && viewModel.uiState.value.members.isNotEmpty() }
    }

    @Test
    fun shopping() = runBlocking<Unit> {
        account()
        val (list, _) = groceries()
        val viewModel = listViewModel(list)
        viewModel.toggleShowChecked()

        showList("Groceries", viewModel)
        capture("list-shopping")
    }

    @Test
    fun checklist() = runBlocking<Unit> {
        account()
        val list = listsRepo.create("home", "Chores", ListKind.CHECKLIST)
        item(list, "Renew passport", due = SCREENSHOT_TODAY.minusDays(3).toString())
        item(list, "Water the plants", due = SCREENSHOT_TODAY.toString())
        item(list, "Book the dentist", due = SCREENSHOT_TODAY.plusDays(5).toString())
        item(list, "Clean the gutters", due = SCREENSHOT_TODAY.plusDays(60).toString())
        item(list, "Take out the bins")
        item(list, "Pay the electricity bill", due = SCREENSHOT_TODAY.minusDays(1).toString(), checked = true)
        val viewModel = listViewModel(list)
        viewModel.toggleShowChecked()

        showList("Chores", viewModel)
        capture("list-checklist")
    }

    @Test
    fun itemDialog() = runBlocking<Unit> {
        account()
        val (list, milk) = groceries()
        val viewModel = listViewModel(list)
        val form = ItemFormViewModel(itemsRepo, listsRepo, accounts.listAccounts(listsRepo)).tracked()

        showList("Groceries", viewModel) { EditItemDialog(itemId = milk, onDismiss = {}, viewModel = form) }
        captureWithDialog("item-dialog")
    }
}
