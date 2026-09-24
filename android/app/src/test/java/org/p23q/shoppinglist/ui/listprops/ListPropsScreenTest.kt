package org.p23q.shoppinglist.ui.listprops

import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.insertTestAccount
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.DeviceIdProvider
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.api.AuthInterceptor
import org.p23q.shoppinglist.core.api.ErrorInterceptor
import org.p23q.shoppinglist.core.api.TokenProvider
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.core.repo.ListsRepo
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.Syncer
import org.p23q.shoppinglist.data.FakeCurrentAccount
import org.p23q.shoppinglist.data.TestServerAddress
import org.p23q.shoppinglist.core.api.ApiSource
import org.p23q.shoppinglist.data.testApiSource
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ListPropsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer

    private val me = "acct-me"

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun `renders the name and category order, and unsubscribe opens a confirm dialog`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount() }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        listsRepo.setCategoryOrder(listId, listOf("dairy"))
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }

        val serverConfigFile = File.createTempFile("listprops_screen_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = TestServerAddress()
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = testApiSource(json, token = { "tok-123" }) { serverConfig.url }
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            apiProvider,
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            FakeCurrentAccount(),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )
        var left = false

        composeTestRule.setContent { ListPropsScreen(onLeft = { left = true }, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Groceries").assertExists()
        composeTestRule.onNodeWithText("dairy").assertExists()

        composeTestRule.onNodeWithText("Leave list").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Leave this list?").assertExists()
        db.close()
        assertEquals(false, left)
    }

    @Test
    fun `typing a note and saving persists it as an LWW edit`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount() }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")

        val serverConfigFile = File.createTempFile("listprops_screen_notes_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = TestServerAddress()
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = testApiSource(json, token = { "tok-123" }) { serverConfig.url }
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            apiProvider,
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            FakeCurrentAccount(),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )

        composeTestRule.setContent { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Notes").assertExists()
        // Scroll the field into view first (T-110 added a Type section above it, pushing Notes off
        // the viewport) — same convention the leave/duplicate tests in this file already use.
        composeTestRule.onNodeWithText("Gate code, store hours, anything worth remembering…")
            .performScrollTo()
            .performTextInput("Gate code: 4471")
        composeTestRule.onNodeWithText("Save notes").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals("Gate code: 4471", listsRepo.getById(listId)!!.notes.value)
        db.close()
    }

    @Test
    fun `tapping Duplicate creates a copy and navigates to it (T-63)`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount() }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Groceries")
        itemsRepo.createItem(listId, "Milk")

        val serverConfigFile = File.createTempFile("listprops_screen_duplicate_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = TestServerAddress()
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = testApiSource(json, token = { "tok-123" }) { serverConfig.url }
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            apiProvider,
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            FakeCurrentAccount(),
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )
        var duplicatedListId: String? = null

        composeTestRule.setContent {
            ListPropsScreen(onLeft = {}, onDuplicated = { duplicatedListId = it }, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Duplicate").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(true, duplicatedListId != null && duplicatedListId != listId)
        db.close()
    }

    /**
     * An expenses list's settings, shown to [me] with [closeVotes] already cast. The roster and
     * votes normally arrive from the server on the list row.
     */
    private suspend fun showExpenseSettings(closeVotes: List<String>): AppDb {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"members": [], "invites": []}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        runBlocking { db.insertTestAccount() }
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db, deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db, deviceId, FakeSyncTrigger())
        val listId = listsRepo.create(TEST_ACCOUNT_ID, "Trip", ListKind.EXPENSES, currency = "EUR")
        db.listDao().upsert(listsRepo.getById(listId)!!.copy(closeVotesJson = Json.encodeToString(closeVotes)))

        val serverConfigFile = File.createTempFile("listprops_screen_vote_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = TestServerAddress()
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = testApiSource(json, token = { "tok-123" }) { serverConfig.url }
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            apiProvider,
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("listprops_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
            FakeCurrentAccount().apply { accountId = me },
            Syncer { SyncResult.Success(0, 0, 0, 0) },
        )

        composeTestRule.setContent { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()
        return db
    }
    @Test
    fun `once I have agreed to close, the name and notes are locked and it says why (T-193)`() = runBlocking {
        val db = showExpenseSettings(closeVotes = listOf(me))

        composeTestRule.onNodeWithText("You've agreed to close this list", substring = true).assertExists()
        composeTestRule.onNodeWithText("Trip").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Gate code, store hours, anything worth remembering…")
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("Save notes").performScrollTo().assertIsNotEnabled()
        db.close()
    }

    @Test
    fun `someone else's vote leaves my name and notes editable`() = runBlocking {
        val db = showExpenseSettings(closeVotes = listOf("acct-other"))

        composeTestRule.onNodeWithText("You've agreed to close this list", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Trip").assertIsEnabled()
        composeTestRule.onNodeWithText("Save notes").performScrollTo().assertIsEnabled()
        db.close()
    }
}
