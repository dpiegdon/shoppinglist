package org.p23q.shoppinglist.ui.list

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.api.AuthInterceptor
import org.p23q.shoppinglist.core.api.ErrorInterceptor
import org.p23q.shoppinglist.core.api.SessionEvents
import org.p23q.shoppinglist.core.api.TokenProvider
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.data.DefaultCurrencyState
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.ShowCheckedStore
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.sync.SyncResult
import org.p23q.shoppinglist.data.sync.SyncStatus
import org.p23q.shoppinglist.data.sync.Syncer
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var apiProvider: ApiProvider

    private fun textStyleOf(text: String) = composeTestRule.onNodeWithText(text).fetchSemanticsNode().let { node ->
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
        val serverConfigFile = File.createTempFile("list_screen_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { "tok-123" }),
            errorInterceptor = ErrorInterceptor(json, SessionEvents()),
            json = json,
        )
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
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        itemsRepo.createItem(listId, "Milk", status = Status.CHECKED)
        itemsRepo.createItem(listId, "Bread", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
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
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
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
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
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
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
        )

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        // Said rather than left blank (T-179), as on expense lists and on the web.
        composeTestRule.onNodeWithText("Nothing on this list yet. Add an item to get started.").assertIsDisplayed()
        // The sync dot moved to the top bar of every screen (T-178); the controls line keeps the rest.
        composeTestRule.onNodeWithContentDescription("Not synced yet").assertDoesNotExist()
        composeTestRule.onNodeWithText("Show checked").assertExists()
    }

    @Test
    fun `the edit icon is a distinct hit target from the row body toggle`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
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
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
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
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        // Simulate what a real sync merge would have set (T-64) — never locally writable.
        db.itemDao().upsert(itemsRepo.getById(itemId)!!.copy(lastTouchedByAccountId = "acc-b"))
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
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
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        db.itemDao().upsert(itemsRepo.getById(itemId)!!.copy(lastTouchedByAccountId = "acc-a"))
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_show_checked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
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
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_fab", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
        )
        var added = false

        composeTestRule.setContent { ListScreen(onAddItem = { added = true }, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        // An icon button now, found by what it is called rather than by a visible label.
        composeTestRule.onNodeWithContentDescription("Add item").performClick()
        assertEquals(true, added)
        // And the full-width button it replaced is gone.
        composeTestRule.onNodeWithText("Add item").assertDoesNotExist()
        db.close()
    }

    @Test
    fun `a quarantined item says on its row that it was not saved, and why (T-210)`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val itemId = itemsRepo.createItem(listId, "Milk", status = Status.TODO)
        // What SyncEngine leaves on a row the server refused with a 422 (T-32, T-200).
        db.itemDao().blockRow(itemId, "invalid_price", null)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_blocked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
        )

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        // The reason where one is mapped, and the mark itself as what TalkBack hears on the row.
        composeTestRule.onNodeWithText("That price isn't valid").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Not saved to the list").assertExists()
        db.close()
    }

    @Test
    fun `an item the server never refused carries no mark (T-210)`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        itemsRepo.createItem(listId, "Bread", status = Status.TODO)
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_unblocked", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
        )

        composeTestRule.setContent { ListScreen(onAddItem = {}, onEditItem = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Bread").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not saved to the list").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Not saved to the list").assertDoesNotExist()
        db.close()
    }

    @Test
    fun `Show checked carries a check while it is on, as on the web (T-174)`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        val viewModel = ListViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            itemsRepo,
            listsRepo,
            Syncer { SyncResult.Success(0, 0, 0, 0) },
            SyncStatus(),
            DefaultCurrencyState(FakeSessionState()),
            ShowCheckedStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("list_screen_check_mark", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            apiProvider,
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
        db.close()
    }
}
