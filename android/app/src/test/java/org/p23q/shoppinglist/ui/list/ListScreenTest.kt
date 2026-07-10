package org.p23q.shoppinglist.ui.list

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
class ListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun textStyleOf(text: String) = composeTestRule.onNodeWithText(text).fetchSemanticsNode().let { node ->
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        results.first().layoutInput.style
    }

    @Test
    fun `checked row renders with a strikethrough in a distinct color, unchecked row does not`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Bread", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            FakeSessionState(),
        )
        viewModel.toggleShowChecked()

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        val checkedStyle = textStyleOf("Milk")
        val uncheckedStyle = textStyleOf("Bread")
        assertEquals(TextDecoration.LineThrough, checkedStyle.textDecoration)
        assertNotEquals(TextDecoration.LineThrough, uncheckedStyle.textDecoration)
        // The checked color is now the theme's error color (T-40), not a fixed literal — assert it
        // differs from the unchecked row's default rather than a hardcoded Color.Red.
        assertNotEquals(checkedStyle.color, uncheckedStyle.color)
    }

    @Test
    fun `Clear checked shows the count and bulk-moves checked items to backlog`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            // Unconfined so clearChecked()'s viewModelScope coroutine completes inline under
            // waitForIdle - a real IO pool races the Compose wait primitives (see AddItemDialogTest / T-29).
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val milk = itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        val eggs = itemsRepo.createItem(listId, "Eggs", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Bread", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            FakeSessionState(),
        )

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        // Count reflects the two checked items even though show-checked is off.
        composeTestRule.onNodeWithText("Clear checked (2)").performClick()
        composeTestRule.waitForIdle()

        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(milk)!!.status.value)
        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(eggs)!!.status.value)
    }

    @Test
    fun `the edit icon is a distinct hit target from the row body toggle`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            FakeSessionState(),
        )
        var editedItemId: String? = null

        composeTestRule.setContent {
            ListScreen(onAddItem = {}, onEditItem = { editedItemId = it }, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Edit Milk").performClick()
        composeTestRule.waitForIdle()

        assertEquals(itemId, editedItemId)
        assertEquals(Status.TODO.wireValue, itemsRepo.getById(itemId)!!.status.value)
        assertFalse(viewModel.uiState.value.undoItemId == itemId)
    }
}
