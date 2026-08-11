package org.p23q.shoppinglist.ui.item

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.Status
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddItemDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `renders the name field, picking a suggestion sets it todo and dismisses`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            // Unconfined (not IO): this test awaits save()'s viewModelScope coroutine, whose only
            // thread hop is the Room query context. On a real IO pool that hop races the Compose
            // wait primitives (waitForIdle returns before the write lands; waitUntil never idles
            // Robolectric's looper for the post-IO continuation) -> flaked under full-suite load.
            // Running DAO calls inline makes save() complete deterministically under waitForIdle. (T-29)
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val existingId = itemsRepo.createItem(listId, "Milk", status = Status.BACKLOG)
        val viewModel = ItemFormViewModel(itemsRepo, listsRepo, FakeSessionState())
        var dismissed = false

        composeTestRule.setContent {
            AddItemDialog(listId = listId, onDismiss = { dismissed = true }, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Add item").assertExists()
        // Types a prefix rather than "Milk" itself, so the suggestion row's text ("Milk") stays
        // the only node matching that exact string - the field's own value would otherwise collide.
        composeTestRule.onNodeWithText("Name").performTextInput("Mil")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Milk").performClick()
        composeTestRule.waitForIdle()

        // Picking a suggestion only prefills; the item isn't put on the list until Save (T-33).
        assertEquals(Status.BACKLOG.wireValue, itemsRepo.getById(existingId)!!.status.value)

        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.waitForIdle()

        assertEquals(true, dismissed)
        assertEquals(Status.TODO.wireValue, itemsRepo.getById(existingId)!!.status.value)
    }

    /**
     * Opening the dialog should leave you able to type immediately.
     *
     * Only the focus half is asserted: whether the soft keyboard is actually on screen is decided
     * by the real IME and the dialog window, neither of which Robolectric simulates. Focus is the
     * part this code controls — the keyboard follows it — so it's the part worth pinning.
     */
    // runBlocking<Unit>, not runBlocking: this body ends in an assertIsFocused() that returns a
    // SemanticsNodeInteraction, and JUnit rejects a test method that doesn't return void — as an
    // InvalidTestClassError that takes the whole class down, not just this method.
    @Test
    fun `the name field is focused as soon as the dialog opens`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val viewModel = ItemFormViewModel(itemsRepo, listsRepo, FakeSessionState())

        composeTestRule.setContent {
            AddItemDialog(listId = listId, onDismiss = {}, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Name").assertIsFocused()
    }
}
