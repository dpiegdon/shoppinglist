package org.p23q.shoppinglist.ui.redeem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.sync.SyncEngine
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RedeemScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `auto-redeems the token from the App Link and reports the list id`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"list_id": "list-42"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val serverConfigFile = File.createTempFile("redeem_screen_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val sessionState = FakeSessionState().apply { token = "tok-123" }
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
        val syncEngine = SyncEngine(db.itemDao(), db.listDao(), apiProvider, sessionState, serverConfig, db)
        val viewModel = RedeemViewModel(apiProvider, syncEngine)
        var redeemedListId: String? = null

        composeTestRule.setContent {
            RedeemScreen(token = "abc.def", onRedeemed = { redeemedListId = it }, onCancel = {}, viewModel = viewModel)
        }
        // See RedeemDialogTest: redeem() makes two real sequential HTTP round trips, so a single
        // waitForIdle() doesn't reliably span both.
        var attempts = 0
        while (redeemedListId == null && attempts < 50) {
            composeTestRule.waitForIdle()
            Thread.sleep(100)
            attempts++
        }

        db.close()
        assertEquals("list-42", redeemedListId)
    }

    @Test
    fun `a redeem failure shows the error and a way back`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error": "invalid_token", "message": "Bad invite link"}"""),
        )

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val serverConfigFile = File.createTempFile("redeem_screen_fail_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val sessionState = FakeSessionState().apply { token = "tok-123" }
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
        val syncEngine = SyncEngine(db.itemDao(), db.listDao(), apiProvider, sessionState, serverConfig, db)
        val viewModel = RedeemViewModel(apiProvider, syncEngine)

        composeTestRule.setContent {
            RedeemScreen(token = "bad-token", onRedeemed = {}, onCancel = {}, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Bad invite link").assertExists()
        composeTestRule.onNodeWithText("Back").assertExists()
        db.close()
    }
}
