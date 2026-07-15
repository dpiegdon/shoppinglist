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
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.Status
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
        itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        listId = listsRepo.createList("Groceries")
    }

    private fun newViewModel(): RegistryViewModel =
        RegistryViewModel(SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)), itemsRepo)

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
