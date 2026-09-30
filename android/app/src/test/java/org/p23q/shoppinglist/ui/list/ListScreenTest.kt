package org.p23q.shoppinglist.ui.list

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.ShowCheckedStore
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.closeWhenIdle
import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.insertTestAccount
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.data.testListAccounts
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.shortDate
import org.p23q.shoppinglist.ui.theme.AccentTextLight
import org.p23q.shoppinglist.ui.theme.MutedLight
import org.p23q.shoppinglist.ui.theme.ShoppingListTheme
import org.p23q.shoppinglist.ui.theme.ThemeVariant
import org.p23q.shoppinglist.ui.theme.brandColorScheme
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var serverUrl: String

    private fun textStyleOf(text: String, useUnmergedTree: Boolean = false) =
        composeTestRule.onNodeWithText(text, useUnmergedTree = useUnmergedTree).fetchSemanticsNode().let { node ->
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        results.first().layoutInput.style
    }

    // T-64: ListViewModel fetches the member roster on init. Default dispatcher answers with an
    // empty roster (no badge, matching the pre-T-64 tests' single-member expectations below); the
    // badge-specific tests override server.dispatcher before setContent.
    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}""")
        }
        server.start()
        serverUrl = server.url("/").toString()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun `checked row renders a strike-through across the whole row, unchecked row does not`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Bread", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )
        viewModel.toggleShowChecked()

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        // The strike spans the whole row (T-63), not just the name text, so there's exactly one
        // strike element for the one checked item — Bread (unchecked) has none.
        composeTestRule.onAllNodesWithTag("checked-item-strike").assertCountEquals(1)
        // The checked color is still the theme's error color (T-40), not a fixed literal — assert it
        // differs from the unchecked row's default rather than a hardcoded Color.Red.
        val checkedStyle = textStyleOf("Milk")
        val uncheckedStyle = textStyleOf("Bread")
        assertNotEquals(checkedStyle.color, uncheckedStyle.color)
    }

    @Test
    fun `an item categorised with the em dash does not collide with the uncategorized group (T-263)`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        // One item left uncategorized (category null) and one whose category is literally the em
        // dash CategoryCanon.UNCATEGORIZED_LABEL uses for display — both used to key the group
        // header on that same dash and crash Compose with a duplicate LazyColumn key.
        itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        val dashItemId = itemsRepo.createItem(listId, "Dashboard", status = Status.TODO)
        itemsRepo.setCategory(dashItemId, "—")
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )

        // Before the fix this setContent throws IllegalArgumentException("Key ... was already
        // used") because both groups' header keyed on the same "header-—" string.
        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        // Both groups render: one item under each of the two now-distinct headers.
        composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dashboard").assertIsDisplayed()
    }

    @Test
    fun `an empty list says so, and its controls line no longer carries the sync dot`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        // Said rather than left blank (T-179), as on expense lists and on the web.
        composeTestRule.onNodeWithText("Nothing on this list yet. Add an item to get started.").assertIsDisplayed()
        // The sync dot moved to the top bar of every screen (T-178); the controls line keeps the rest.
        composeTestRule.onNodeWithContentDescription("Not synced yet").assertDoesNotExist()
        composeTestRule.onNodeWithText("Show checked").assertExists()
        // Its icon buttons are full 48dp targets (C6, T-343), not shrunk to 40dp.
        for (label in listOf("All items", "List properties")) {
            composeTestRule.onNodeWithContentDescription(label).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `the edit icon is a distinct hit target from the row body toggle`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
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

    @Test
    fun `long-press on the row body opens the editor instead of marking it done (T-79)`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )
        var editedItemId: String? = null

        composeTestRule.setContent {
            ListScreen(onAddItem = {}, onEditItem = { editedItemId = it }, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Milk").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // The long-press opens the editor and leaves the item's status untouched (not checked off).
        assertEquals(itemId, editedItemId)
        assertEquals(Status.TODO.wireValue, itemsRepo.getById(itemId)!!.status.value)
    }

    @Test
    fun `last-touched-by badge shows the right initials when the list has 2+ members`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody(
                """{"members": [""" +
                    """{"account_id": "acc-a", "email": "a@example.com", "initials": "A", "joined_at": 1},""" +
                    """{"account_id": "acc-b", "email": "b@example.com", "initials": "B", "joined_at": 2}""" +
                    """], "invites": []}""",
            )
        }
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        // Simulate what a real sync merge would have set (T-64) — never locally writable.
        db.itemDao().upsert(itemsRepo.getById(itemId)!!.copy(lastTouchedByAccountId = "acc-b"))
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        // The member roster is a real MockWebServer round trip fired from ListViewModel's init
        // (T-64), which waitForIdle() alone doesn't wait for (T-96) - poll the observed state,
        // re-idling Compose each attempt so a response that lands late still gets picked up.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.members.size == 2
        }

        composeTestRule.onNodeWithText("B").assertExists()
        composeTestRule.onNodeWithContentDescription("Last touched by b@example.com").assertExists()
        Unit
    }

    @Test
    fun `last-touched-by badge is hidden on a solo list even if last_touched_by is set`() = runBlocking {
        // Default dispatcher (see setUp) answers with an empty roster — the common solo-list case.
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        db.itemDao().upsert(itemsRepo.getById(itemId)!!.copy(lastTouchedByAccountId = "acc-a"))
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Last touched by a@example.com").assertDoesNotExist()
    }

    @Test
    fun `Add is the floating button every list has, and opens the add dialog (T-168)`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_fab", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )
        var added = false

        composeTestRule.setContent { ListScreen(onAddItem = { added = true }, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        // An icon button now, found by what it is called rather than by a visible label.
        composeTestRule.onNodeWithContentDescription("Add item").performClick()
        assertEquals(true, added)
        // And the full-width button it replaced is gone.
        composeTestRule.onNodeWithText("Add item").assertDoesNotExist()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `a quarantined item says on its row that it was not saved, and why (T-210)`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        // What SyncEngine leaves on a row the server refused with a 422 (T-32, T-200).
        db.itemDao().blockRow(itemId, "invalid_price", null)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_blocked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        // The reason where one is mapped, and the mark itself as what TalkBack hears on the row.
        composeTestRule.onNodeWithText("That price isn't valid").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Not saved to the list").assertExists()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `an item the server never refused carries no mark (T-210)`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        itemsRepo.createItem(listId, "Bread", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_unblocked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Bread").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not saved to the list").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Not saved to the list").assertDoesNotExist()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `Show checked carries a check while it is on, as on the web (T-174)`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount(testAccount(serverUrl = serverUrl)) }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_check_mark", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()
        val wasOn = viewModel.uiState.value.showChecked

        // The unmerged tree: the chip merges its children into one node, and a child's test tag does
        // not survive that merge.

        composeTestRule.onAllNodesWithTag("show-checked-mark", useUnmergedTree = true).assertCountEquals(if (wasOn) 1 else 0)
        composeTestRule.onNodeWithText("Show checked").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("show-checked-mark", useUnmergedTree = true).assertCountEquals(if (wasOn) 0 else 1)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    private suspend fun dueScreen(kind: String, seed: suspend (ItemsRepo, String) -> Unit): Pair<AppDb, ListViewModel> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db.insertTestAccount(testAccount(serverUrl = serverUrl))
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Chores", kind = kind)
        seed(itemsRepo, listId)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            testListAccounts(db, listsRepo),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_due", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )
        return db to viewModel
    }

    @Test
    fun `a checklist row shows its due date, red when past, highlighted today, muted ahead and when checked (T-323)`() = runBlocking<Unit> {
        val today = LocalDate.now()
        val past = today.minusDays(1).toString()
        val checkedPast = today.minusDays(2).toString()
        val ahead = today.plusDays(3).toString()
        val (db, viewModel) = dueScreen(ListKind.CHECKLIST) { items, listId ->
            items.setDue(items.createItem(listId, "Late"), past)
            items.setDue(items.createItem(listId, "Now"), today.toString())
            items.setDue(items.createItem(listId, "Later"), ahead)
            items.setDue(items.createItem(listId, "Done", status = Status.CHECKED), checkedPast)
            items.createItem(listId, "Whenever")
        }
        viewModel.toggleShowChecked()

        composeTestRule.setContent {
            ShoppingListTheme(darkTheme = false) {
                ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        val iso = today.toString()
        fun text(date: String) = shortDate(date, Locale.getDefault(), iso)
        // Under the app's own theme: today is the highlighted text colour (T-324), readable on
        // white, not the lighter accent the buttons are drawn in.
        val scheme = brandColorScheme(ThemeVariant.LIGHT)
        assertEquals(scheme.error, textStyleOf(text(past), useUnmergedTree = true).color)
        assertEquals(AccentTextLight, textStyleOf(text(iso), useUnmergedTree = true).color)
        assertEquals(MutedLight, textStyleOf(text(ahead), useUnmergedTree = true).color)
        assertEquals(MutedLight, textStyleOf(text(checkedPast), useUnmergedTree = true).color)
        // One date per item that has one, and the words only as what TalkBack hears.
        composeTestRule.onAllNodesWithTag("item-due", useUnmergedTree = true).assertCountEquals(4)
        composeTestRule.onNodeWithContentDescription("${text(past)}, Overdue", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("${text(iso)}, Due today", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Overdue").assertDoesNotExist()
        composeTestRule.onNodeWithText("Due today").assertDoesNotExist()
        // Small: a fifth under the name's size, never larger or bolder.
        assertTrue(textStyleOf(text(ahead), useUnmergedTree = true).fontSize < textStyleOf("Later", useUnmergedTree = true).fontSize)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `a shopping list row shows no due date, though the item keeps one (T-323)`() = runBlocking<Unit> {
        val due = LocalDate.now().minusDays(1).toString()
        val (db, viewModel) = dueScreen(ListKind.SHOPPING) { items, listId ->
            items.setDue(items.createItem(listId, "Milk"), due)
        }

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("item-due", useUnmergedTree = true).assertCountEquals(0)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `a row is a checkbox to TalkBack that says whether the item is checked (T-339)`() = runBlocking<Unit> {
        val (db, viewModel) = dueScreen(ListKind.SHOPPING) { items, listId ->
            items.createItem(listId, "Milk")
            items.createItem(listId, "Bread", status = Status.CHECKED)
        }
        viewModel.toggleShowChecked()

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Milk")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        composeTestRule.onNodeWithText("Bread")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        // The edit button keeps the full 48dp target.
        composeTestRule.onAllNodesWithTag("item-edit")[0].assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `the uncategorised group has no heading when it is the only group (T-339)`() = runBlocking<Unit> {
        val (db, viewModel) = dueScreen(ListKind.SHOPPING) { items, listId ->
            items.createItem(listId, "Milk")
        }
        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Milk").assertIsDisplayed()
        composeTestRule.onNodeWithTag("group-heading-uncategorized", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("—").assertDoesNotExist()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `beside other groups the uncategorised heading is muted and read as No category (T-339)`() = runBlocking<Unit> {
        val (db, viewModel) = dueScreen(ListKind.SHOPPING) { items, listId ->
            items.createItem(listId, "Milk")
            items.setCategory(items.createItem(listId, "Soap"), "Bathroom")
        }
        composeTestRule.setContent {
            ShoppingListTheme(darkTheme = false) {
                ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Bathroom").assertExists()
        composeTestRule.onNodeWithTag("group-heading-uncategorized", useUnmergedTree = true)
            .assertContentDescriptionEquals("No category")
        assertEquals(MutedLight, textStyleOf("—", useUnmergedTree = true).color)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }

    @Test
    fun `the quantity and price line is muted, as every secondary line is (T-339)`() = runBlocking<Unit> {
        val (db, viewModel) = dueScreen(ListKind.SHOPPING) { items, listId ->
            items.setQuantity(items.createItem(listId, "Milk"), "2 l")
        }
        composeTestRule.setContent {
            ShoppingListTheme(darkTheme = false) {
                ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(MutedLight, textStyleOf("2 l", useUnmergedTree = true).color)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel))
    }
}
