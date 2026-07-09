package org.p23q.shoppinglist.ui.registry

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegistryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `lists every item, searches, and delete arms undo`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        itemsRepo.createItem(listId, "Someday item", status = Status.BACKLOG)
        val viewModel = RegistryViewModel(SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)), itemsRepo)
        var editedItemId: String? = null

        composeTestRule.setContent {
            RegistryScreen(onEditItem = { editedItemId = it }, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Milk").assertExists()
        composeTestRule.onNodeWithText("Someday item").assertExists()

        composeTestRule.onNodeWithText("Search").performTextInput("Milk")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Someday item").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Delete Milk").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Milk", viewModel.uiState.value.undoItemName)
        assertEquals(null, editedItemId)
    }
}
