package org.p23q.shoppinglist.ui.list

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.sync.SyncResult
import org.p23q.shoppinglist.data.sync.SyncStatus
import org.p23q.shoppinglist.data.sync.Syncer
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDb
    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listsRepo: ListsRepo
    private lateinit var sessionState: FakeSessionState
    private lateinit var listId: String
    private val syncStatus = SyncStatus()
    private val syncer = RecordingSyncer()

    private class RecordingSyncer : Syncer {
        var calls = 0
        override suspend fun syncNow(fullLists: List<String>): SyncResult {
            calls++
            return SyncResult.Success(0, 0, 0, 0)
        }
    }

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        sessionState = FakeSessionState()
        listId = listsRepo.createList("Groceries")
    }

    private fun newViewModel(): ListViewModel =
        ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            syncer,
            syncStatus,
            sessionState,
        )

    @Test
    fun `groups follow category_order, then leftover categories alphabetically, uncategorized last`() = runTest {
        listsRepo.setCategoryOrder(listId, listOf("dairy", "bakery"))
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }
        itemsRepo.createItem(listId, "Bread").also { itemsRepo.setCategory(it, "bakery") }
        itemsRepo.createItem(listId, "Soap").also { itemsRepo.setCategory(it, "hygiene") }
        itemsRepo.createItem(listId, "Nails").also { itemsRepo.setCategory(it, "hardware") }
        itemsRepo.createItem(listId, "Loose item")

        val groups = newViewModel().uiState.first { it.groups.isNotEmpty() }.groups

        assertEquals(listOf("dairy", "bakery", "hardware", "hygiene", null), groups.map { it.category })
    }

    @Test
    fun `items sort alphabetically within a category`() = runTest {
        itemsRepo.createItem(listId, "Zucchini").also { itemsRepo.setCategory(it, "produce") }
        itemsRepo.createItem(listId, "apple").also { itemsRepo.setCategory(it, "produce") }
        itemsRepo.createItem(listId, "Banana").also { itemsRepo.setCategory(it, "produce") }

        val groups = newViewModel().uiState.first { it.groups.isNotEmpty() }.groups

        assertEquals(listOf("apple", "Banana", "Zucchini"), groups.single().items.map { it.name.value })
    }

    @Test
    fun `backlog items never appear regardless of the show-checked toggle`() = runTest {
        itemsRepo.createItem(listId, "Someday item", status = Status.BACKLOG)
        val viewModel = newViewModel()

        val hiddenState = viewModel.uiState.first { it.listName == "Groceries" }
        assertTrue(hiddenState.groups.none { group -> group.items.any { it.name.value == "Someday item" } })

        viewModel.toggleShowChecked()

        assertTrue(viewModel.uiState.value.groups.none { group -> group.items.any { it.name.value == "Someday item" } })
    }

    @Test
    fun `checked items are hidden by default and appear once the toggle is on`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        val viewModel = newViewModel()

        val initial = viewModel.uiState.first { it.listName == "Groceries" }
        assertTrue(initial.groups.none { g -> g.items.any { it.id == itemId } })

        viewModel.toggleShowChecked()

        val afterToggle = viewModel.uiState.first { state -> state.groups.any { g -> g.items.any { it.id == itemId } } }
        assertTrue(afterToggle.showChecked)
    }

    @Test
    fun `checkOff marks todo item checked and arms the undo snackbar`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.groups.isNotEmpty() }

        viewModel.checkOff(itemId).join()

        assertEquals(Status.CHECKED.wireValue, itemsRepo.getById(itemId)!!.status.value)
        assertEquals(itemId, viewModel.uiState.value.undoItemId)
        assertEquals("Milk", viewModel.uiState.value.undoItemName)
    }

    @Test
    fun `undoCheckOff restores the item to todo and clears the snackbar`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.groups.isNotEmpty() }
        viewModel.checkOff(itemId).join()

        viewModel.undoCheckOff().join()

        assertEquals(Status.TODO.wireValue, itemsRepo.getById(itemId)!!.status.value)
        assertNull(viewModel.uiState.value.undoItemId)
    }

    @Test
    fun `uncheck reverts a checked item back to todo`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.listName == "Groceries" }

        viewModel.uncheck(itemId).join()

        assertEquals(Status.TODO.wireValue, itemsRepo.getById(itemId)!!.status.value)
    }

    @Test
    fun `checkedCount counts checked items regardless of the show-checked toggle (T-35)`() = runTest {
        itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Eggs", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Bread", status = Status.TODO)
        val viewModel = newViewModel()

        assertEquals(2, viewModel.uiState.first { it.checkedCount == 2 }.checkedCount)

        // Toggling show-checked changes visibility, not the count that drives the Clear-checked action.
        viewModel.toggleShowChecked()
        assertEquals(2, viewModel.uiState.value.checkedCount)
    }

    @Test
    fun `clearChecked moves every checked item to backlog and arms the undo (T-35)`() = runTest {
        val a = itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        val b = itemsRepo.createItem(listId, "Eggs", status = Status.CHECKED)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.checkedCount == 2 }

        viewModel.clearChecked().join()

        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(a)!!.status.value)
        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(b)!!.status.value)
        assertEquals(setOf(a, b), viewModel.uiState.value.clearedCheckedIds.toSet())
        // Nothing checked any more -> the action's count drops to 0 (button hides).
        assertEquals(0, viewModel.uiState.first { it.checkedCount == 0 }.checkedCount)
    }

    @Test
    fun `refresh runs a sync and clears the refreshing flag (T-36)`() = runTest {
        val viewModel = newViewModel()
        viewModel.uiState.first { it.listName == "Groceries" }

        viewModel.refresh().join()

        assertEquals(1, syncer.calls)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `sync status flows into the list ui state (T-47)`() = runTest {
        val viewModel = newViewModel()

        syncStatus.succeeded(at = 5_000L, pending = 1, blocked = 0)

        assertEquals(5_000L, viewModel.uiState.first { it.sync.lastSyncAt == 5_000L }.sync.lastSyncAt)
    }

    @Test
    fun `undoClearChecked puts the cleared items back to checked (T-35)`() = runTest {
        val a = itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.checkedCount == 1 }
        viewModel.clearChecked().join()
        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(a)!!.status.value)

        viewModel.undoClearChecked().join()

        assertEquals(Status.CHECKED.wireValue, itemsRepo.getById(a)!!.status.value)
        assertTrue(viewModel.uiState.value.clearedCheckedIds.isEmpty())
    }

    @Test
    fun `price renders with the item currency, falling back to the account default when absent`() = runTest {
        val withCurrency = itemsRepo.createItem(listId, "Milk")
        itemsRepo.setPrice(withCurrency, amount = "1.99", currency = "EUR")
        val withoutCurrency = itemsRepo.createItem(listId, "Bread")
        itemsRepo.setPrice(withoutCurrency, amount = "2.50", currency = null)
        sessionState.defaultCurrency = "USD"

        val groups = newViewModel().uiState.first { it.groups.isNotEmpty() }.groups
        val items = groups.flatMap { it.items }.associateBy { it.name.value }

        assertEquals("1.99 EUR", formatPrice(items.getValue("Milk"), "USD"))
        assertEquals("2.50 USD", formatPrice(items.getValue("Bread"), "USD"))
    }

    @Test
    fun `a currency change made in Settings is reflected by the next list view (A10)`() = runTest {
        val itemId = itemsRepo.createItem(listId, "Bread")
        itemsRepo.setPrice(itemId, amount = "2.50", currency = null)
        sessionState.defaultCurrency = "USD"
        val beforeSettingsChange = newViewModel().uiState.first { it.groups.isNotEmpty() }
        assertEquals("USD", beforeSettingsChange.defaultCurrency)

        // Simulates SettingsViewModel.updateCurrency()'s effect: it writes straight through to the
        // same SessionState this app-wide singleton represents, not a copy - so any ListViewModel
        // constructed afterwards (i.e. next time the user opens a list) picks it up automatically.
        sessionState.defaultCurrency = "EUR"

        val afterSettingsChange = newViewModel().uiState.first { it.groups.isNotEmpty() }
        assertEquals("EUR", afterSettingsChange.defaultCurrency)
        val item = afterSettingsChange.groups.flatMap { it.items }.single { it.id == itemId }
        assertEquals("2.50 EUR", formatPrice(item, afterSettingsChange.defaultCurrency))
    }

    @Test
    fun `the list name updates live on rename, without recreating the view model (T-34)`() = runTest {
        val viewModel = newViewModel()
        assertEquals("Groceries", viewModel.uiState.first { it.listName == "Groceries" }.listName)

        listsRepo.rename(listId, "Weekly shop")

        assertEquals("Weekly shop", viewModel.uiState.first { it.listName == "Weekly shop" }.listName)
    }

    @Test
    fun `changing category_order re-groups the open list live (T-34)`() = runTest {
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }
        itemsRepo.createItem(listId, "Bread").also { itemsRepo.setCategory(it, "bakery") }
        val viewModel = newViewModel()
        // No explicit order yet -> alphabetical.
        assertEquals(
            listOf("bakery", "dairy"),
            viewModel.uiState.first { it.groups.size == 2 }.groups.map { it.category },
        )

        listsRepo.setCategoryOrder(listId, listOf("dairy", "bakery"))

        assertEquals(
            listOf("dairy", "bakery"),
            viewModel.uiState
                .first { s -> s.groups.map { it.category } == listOf("dairy", "bakery") }
                .groups.map { it.category },
        )
    }
}
