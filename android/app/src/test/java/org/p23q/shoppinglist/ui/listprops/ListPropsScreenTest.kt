package org.p23q.shoppinglist.ui.listprops

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
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.repo.ItemsRepo
import org.p23q.shoppinglist.data.repo.ListsRepo
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ListPropsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer

    @After
    fun tearDown() {
        server.shutdown()
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
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        listsRepo.setCategoryOrder(listId, listOf("dairy"))
        itemsRepo.createItem(listId, "Milk").also { itemsRepo.setCategory(it, "dairy") }

        val serverConfigFile = File.createTempFile("listprops_screen_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { "tok-123" }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            apiProvider,
        )
        var left = false

        composeTestRule.setContent { ListPropsScreen(onLeft = { left = true }, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Groceries").assertExists()
        composeTestRule.onNodeWithText("dairy").assertExists()

        composeTestRule.onNodeWithText("Unsubscribe").performScrollTo().performClick()
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
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")

        val serverConfigFile = File.createTempFile("listprops_screen_notes_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { "tok-123" }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            apiProvider,
        )

        composeTestRule.setContent { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Notes").assertExists()
        composeTestRule.onNodeWithText("Gate code, store hours, anything worth remembering…")
            .performTextInput("Gate code: 4471")
        composeTestRule.onNodeWithText("Save notes").performClick()
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
        val deviceId = DeviceIdProvider { "device-1" }
        val itemsRepo = ItemsRepo(db.itemDao(), deviceId, FakeSyncTrigger())
        val listsRepo = ListsRepo(db.listDao(), deviceId, FakeSyncTrigger())
        val listId = listsRepo.createList("Groceries")
        itemsRepo.createItem(listId, "Milk")

        val serverConfigFile = File.createTempFile("listprops_screen_duplicate_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { "tok-123" }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to listId)),
            listsRepo,
            itemsRepo,
            apiProvider,
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
}
