package org.p23q.shoppinglist.ui.registry

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegistryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var itemsRepo: ItemsRepo
    private lateinit var listId: String

    @Before
    fun setUp() = runTest(mainDispatcherRule.dispatcher) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        listId = listsRepo.createList("Groceries")
    }

    private fun newViewModel(): RegistryViewModel =
        RegistryViewModel(SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)), itemsRepo)

    @Test
    fun `items come out in name order, ignoring case (T-137)`() = runTest(mainDispatcherRule.dispatcher) {
        // Inserted deliberately out of order and with mixed case: the DAO query has no ORDER BY,
        // so without the ViewModel's sort this arrives in insertion order.
        itemsRepo.createItem(listId, "cherries", status = Status.TODO)
        itemsRepo.createItem(listId, "Apples", status = Status.BACKLOG)
        itemsRepo.createItem(listId, "bananas", status = Status.CHECKED)

        val items = newViewModel().uiState.first { it.items.size == 3 }.items

        assertEquals(listOf("Apples", "bananas", "cherries"), items.map { it.name.value })
    }

    @Test
    fun `accented names sort with their base letter, not after Z (T-137)`() = runTest(mainDispatcherRule.dispatcher) {
        // The reason this is a Collator and not SQLite's COLLATE NOCASE: NOCASE case-folds ASCII
        // only, so "Äpfel" would compare by code point and land after every plain-ASCII name.
        // This app ships eight non-English locales, so that is the common case, not the exotic one.
        itemsRepo.createItem(listId, "Zucker", status = Status.TODO)
        itemsRepo.createItem(listId, "Äpfel", status = Status.TODO)
        itemsRepo.createItem(listId, "Butter", status = Status.TODO)

        val items = newViewModel().uiState.first { it.items.size == 3 }.items

        assertEquals(listOf("Äpfel", "Butter", "Zucker"), items.map { it.name.value })
    }

    @Test
    fun `the sort survives a search (T-137)`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "milk chocolate", status = Status.TODO)
        itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        itemsRepo.createItem(listId, "buttermilk", status = Status.TODO)

        val viewModel = newViewModel()
        viewModel.uiState.first { it.items.size == 3 }
        viewModel.onQueryChange("milk")

        // Filtering re-runs the query, so the ordering has to be applied to the filtered flow too,
        // not once at load.
        val items = viewModel.uiState.first { it.query == "milk" && it.items.size == 3 }.items
        assertEquals(listOf("buttermilk", "Milk", "milk chocolate"), items.map { it.name.value })
    }

    @Test
    fun `shows every item regardless of status when the query is empty`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        itemsRepo.createItem(listId, "Bread", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Someday item", status = Status.BACKLOG)

        val items = newViewModel().uiState.first { it.items.size == 3 }.items

        assertEquals(setOf("Milk", "Bread", "Someday item"), items.map { it.name.value }.toSet())
    }

    @Test
    fun `search narrows items by name case-insensitively across every status`() = runTest(mainDispatcherRule.dispatcher) {
        itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        itemsRepo.createItem(listId, "milk chocolate", status = Status.BACKLOG)
        itemsRepo.createItem(listId, "Bread", status = Status.CHECKED)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.items.size == 3 }

        viewModel.onQueryChange("MILK")

        val items = viewModel.uiState.first { it.items.size == 2 }.items
        assertEquals(setOf("Milk", "milk chocolate"), items.map { it.name.value }.toSet())
    }

    @Test
    fun `deleteItem tombstones the item, arms undo, and it drops out of results`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.items.isNotEmpty() }

        viewModel.deleteItem(itemId).join()

        assertTrue(itemsRepo.getById(itemId)!!.deleted.value)
        assertEquals(itemId, viewModel.uiState.value.undoItemId)
        assertEquals("Milk", viewModel.uiState.value.undoItemName)
        val items = viewModel.uiState.first { it.items.isEmpty() }.items
        assertTrue(items.isEmpty())
    }

    @Test
    fun `undoDelete restores the item and it reappears in results`() = runTest(mainDispatcherRule.dispatcher) {
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = newViewModel()
        viewModel.uiState.first { it.items.isNotEmpty() }
        viewModel.deleteItem(itemId).join()
        viewModel.uiState.first { it.items.isEmpty() }

        viewModel.undoDelete().join()

        assertTrue(itemsRepo.getById(itemId)!!.deleted.value.not())
        assertNull(viewModel.uiState.value.undoItemId)
        val items = viewModel.uiState.first { it.items.isNotEmpty() }.items
        assertEquals(listOf("Milk"), items.map { it.name.value })
    }
}
