package org.p23q.shoppinglist.ui.list

import androidx.compose.ui.graphics.Color
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
    fun `checked row renders with a red strikethrough, unchecked row does not`() = runBlocking {
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
            FakeSessionState(),
        )
        viewModel.toggleShowChecked()

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        val checkedStyle = textStyleOf("Milk")
        val uncheckedStyle = textStyleOf("Bread")
        assertEquals(TextDecoration.LineThrough, checkedStyle.textDecoration)
        assertEquals(Color.Red, checkedStyle.color)
        assertNotEquals(TextDecoration.LineThrough, uncheckedStyle.textDecoration)
        assertNotEquals(Color.Red, uncheckedStyle.color)
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
