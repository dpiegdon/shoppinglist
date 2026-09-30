package org.p23q.shoppinglist.ui.registry

import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
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
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
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
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount() }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
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

    @Test
    fun `the status is muted text, and a search with no match says so (T-342)`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount() }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        val viewModel = RegistryViewModel(SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)), itemsRepo)

        composeTestRule.setContent {
            RegistryScreen(onEditItem = {}, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        // Part of the row, which opens the editor; not a chip of its own that looks like a control.
        composeTestRule.onNodeWithText("Todo", useUnmergedTree = true).assertExists()
        composeTestRule
            .onAllNodes(hasText("Todo") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertCountEquals(0)
        composeTestRule.onNodeWithText("No items found.").assertDoesNotExist()

        composeTestRule.onNodeWithText("Search").performTextInput("Bread")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("No items found.").assertIsDisplayed()
    }
}
