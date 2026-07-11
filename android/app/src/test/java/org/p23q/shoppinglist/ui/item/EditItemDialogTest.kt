package org.p23q.shoppinglist.ui.item

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditItemDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `renders prefilled fields, delete asks to confirm, then tombstones and dismisses`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk")
        itemsRepo.setCategory(itemId, "dairy")
        val viewModel = ItemFormViewModel(itemsRepo, FakeSessionState())
        var dismissed = false

        composeTestRule.setContent {
            EditItemDialog(itemId = itemId, onDismiss = { dismissed = true }, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Edit item").assertExists()
        composeTestRule.onNodeWithText("Milk").assertExists()
        composeTestRule.onNodeWithText("dairy").assertExists()

        // The dialog's field Column is taller than Robolectric's simulated viewport, so the Delete
        // button starts scrolled out of view - performScrollTo() brings it into the visible area
        // before performClick() computes a click position, matching how a real user would scroll
        // to reach it. Confirming the tombstone itself is already covered directly in
        // ItemFormViewModelTest.
        composeTestRule.onNodeWithText("Delete").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(viewModel.uiState.value.isDeleteConfirmOpen)
        assertEquals(false, dismissed)
    }
}
