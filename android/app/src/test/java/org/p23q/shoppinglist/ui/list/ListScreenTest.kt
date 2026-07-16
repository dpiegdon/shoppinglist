package org.p23q.shoppinglist.ui.list

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.p23q.shoppinglist.data.DefaultCurrencyState
import org.p23q.shoppinglist.data.ShowCheckedStore
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.SessionEvents
import org.p23q.shoppinglist.data.api.TokenProvider
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
        server.shutdown()
    }

    @Test
    fun `checked row renders a strike-through across the whole row, unchecked row does not`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
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
    fun `sync status is a marker on the top controls line, not its own line`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
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

        // A fresh SyncStatus() has never synced (T-63): the marker carries that sentence as its
        // content description, and "Show checked" (the top row it shares a line with) still exists.
        composeTestRule.onNodeWithContentDescription("Not synced yet").assertExists()
        composeTestRule.onNodeWithText("Show checked").assertExists()
        composeTestRule.onNodeWithText("Not synced yet").assertDoesNotExist()
    }

    @Test
    fun `the edit icon is a distinct hit target from the row body toggle`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
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
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
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
        composeTestRule.waitForIdle()

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
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
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
}
