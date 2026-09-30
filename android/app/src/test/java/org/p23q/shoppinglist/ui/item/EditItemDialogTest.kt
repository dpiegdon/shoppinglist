package org.p23q.shoppinglist.ui.item

import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.closeWhenIdle
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.testListAccounts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.p23q.shoppinglist.core.db.Status
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditItemDialogTest {
    /** An account whose server keeps due dates (T-327), as the due-date tests need. */
    private val dueDatesAccount = testAccount().copy(serverVersion = "3.5.0")


    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `renders prefilled fields, delete asks to confirm, then tombstones and dismisses`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount() }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk")
        itemsRepo.setCategory(itemId, "dairy")
        val viewModel = ItemFormViewModel(itemsRepo, listsRepo, testListAccounts(db, listsRepo))
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

    @Test
    fun `an item the server refused opens with the reason at the top (T-210)`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount() }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk")
        // What SyncEngine leaves on a row the server refused with a 422 (T-32, T-200).
        db.itemDao().blockRow(itemId, "invalid_price", null)
        val viewModel = ItemFormViewModel(itemsRepo, listsRepo, testListAccounts(db, listsRepo))

        composeTestRule.setContent { EditItemDialog(itemId = itemId, onDismiss = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Not saved to the list").assertExists()
        composeTestRule.onNodeWithText("That price isn't valid").assertExists()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    private fun database(): AppDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Unconfined)
        .build()

    @Test
    fun `a checklist item shows its due date on one row, and the cross clears it (T-323)`() = runBlocking<Unit> {
        val db = database()
        db.insertTestAccount(dueDatesAccount)
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Chores", kind = ListKind.CHECKLIST)
        val itemId = itemsRepo.createItem(listId, "Water plants")
        itemsRepo.setDue(itemId, "2026-10-03")
        val viewModel = ItemFormViewModel(itemsRepo, listsRepo, testListAccounts(db, listsRepo))

        composeTestRule.setContent { EditItemDialog(itemId = itemId, onDismiss = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("item-due-row").performScrollTo()
        composeTestRule.onNodeWithText("Due").assertExists()
        composeTestRule.onNodeWithText("Oct 3, 2026").assertExists()
        composeTestRule.onNodeWithContentDescription("No due date").performClick()
        composeTestRule.waitForIdle()

        assertNull(viewModel.uiState.value.due)
        composeTestRule.onNodeWithText("Oct 3, 2026").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("No due date").assertDoesNotExist()
        // Empty again: the calendar button that opens the picker, named by the label.
        composeTestRule.onNodeWithContentDescription("Due").assertExists()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `a shopping list's item has no due row, even with a due date stored (T-323)`() = runBlocking<Unit> {
        val db = database()
        db.insertTestAccount(dueDatesAccount)
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk")
        itemsRepo.setDue(itemId, "2026-10-03")
        val viewModel = ItemFormViewModel(itemsRepo, listsRepo, testListAccounts(db, listsRepo))

        composeTestRule.setContent { EditItemDialog(itemId = itemId, onDismiss = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Milk").assertExists()
        composeTestRule.onAllNodesWithTag("item-due-row").assertCountEquals(0)
        composeTestRule.onNodeWithText("Oct 3, 2026").assertDoesNotExist()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `on an account whose server drops due dates the item's date is shown read-only, with the reason (T-327)`() = runBlocking<Unit> {
        val db = database()
        db.insertTestAccount(testAccount().copy(serverVersion = "3.4.0"))
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Chores", kind = ListKind.CHECKLIST)
        val dated = itemsRepo.createItem(listId, "Water plants")
        itemsRepo.setDue(dated, "2026-10-03")
        val viewModel = ItemFormViewModel(itemsRepo, listsRepo, testListAccounts(db, listsRepo))
        var itemId by mutableStateOf(dated)

        composeTestRule.setContent { EditItemDialog(itemId = itemId, onDismiss = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("item-due-row").performScrollTo()
        composeTestRule.onNodeWithText("Oct 3, 2026").assertExists()
        composeTestRule.onNodeWithText("Due dates need server 3.5.0").assertExists()
        composeTestRule.onAllNodesWithTag("item-due-pick").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("item-due-clear").assertCountEquals(0)

        // Without a date there is nothing to show, and nothing is offered.
        itemId = itemsRepo.createItem(listId, "Sweep")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Sweep").assertExists()
        composeTestRule.onAllNodesWithTag("item-due-row").assertCountEquals(0)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `status is one segmented choice, Backlog then Todo then Checked, as on the web (T-339)`() = runBlocking<Unit> {
        val db = database()
        db.insertTestAccount()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk")
        val viewModel = ItemFormViewModel(itemsRepo, listsRepo, testListAccounts(db, listsRepo))

        composeTestRule.setContent { EditItemDialog(itemId = itemId, onDismiss = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("item-status").performScrollTo()

        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        fun left(label: String) =
            composeTestRule.onNode(hasText(label) and radio).fetchSemanticsNode().boundsInRoot.left
        assertTrue(left("Backlog") < left("Todo"))
        assertTrue(left("Todo") < left("Checked"))
        composeTestRule.onNode(hasText("Todo") and radio).assertIsSelected()

        composeTestRule.onNode(hasText("Backlog") and radio).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Status.BACKLOG, viewModel.uiState.value.status)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `pressing anywhere on a store chip removes the store (T-339)`() = runBlocking<Unit> {
        val db = database()
        db.insertTestAccount()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk")
        itemsRepo.setStores(itemId, listOf("Aldi"))
        val viewModel = ItemFormViewModel(itemsRepo, listsRepo, testListAccounts(db, listsRepo))

        composeTestRule.setContent { EditItemDialog(itemId = itemId, onDismiss = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        // The chip says what pressing it does, and the label itself is the target.
        composeTestRule.onNodeWithContentDescription("Remove Aldi").assertExists()
        composeTestRule.onNodeWithTag("store-chip").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(emptyList<String>(), viewModel.uiState.value.stores)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }
}
