package org.p23q.shoppinglist.ui.redeem

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
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
class RedeemViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var sessionState: FakeSessionState
    private lateinit var apiProvider: ApiProvider
    private lateinit var syncEngine: SyncEngine

    @Before
    fun setUp() = runTest {
        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

        val serverConfigFile = File.createTempFile("redeem_vm_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())

        sessionState = FakeSessionState().apply { token = "tok-123" }
        val json = Json { ignoreUnknownKeys = true }
        apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
        syncEngine = SyncEngine(db.itemDao(), db.listDao(), apiProvider, sessionState, serverConfig, db)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun newViewModel(): RedeemViewModel = RedeemViewModel(apiProvider, syncEngine)

    @Test
    fun `redeem with a blank token is rejected locally without a network call`() = runTest {
        val viewModel = newViewModel()

        val job = viewModel.redeem()

        assertNull(job)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `redeem success syncs the newly shared list and reports its id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"list_id": "list-42"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"cursor": 1, "changes": {"lists": [], "items": []}}"""))
        val viewModel = newViewModel()
        viewModel.onTokenChange("abc.def")

        viewModel.redeem()?.join()

        assertEquals("list-42", viewModel.uiState.value.redeemedListId)
        assertNull(viewModel.uiState.value.errorMessage)
        val syncRequest = server.takeRequest()
        assertEquals("/api/v1/invites/redeem", syncRequest.path)
        val followUp = server.takeRequest()
        assertEquals("/api/v1/sync", followUp.path)
    }

    @Test
    fun `redeem failure surfaces the server's error message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error": "invite_used", "message": "This invite was already used"}"""),
        )
        val viewModel = newViewModel()
        viewModel.onTokenChange("abc.def")

        viewModel.redeem()?.join()

        assertEquals("This invite was already used", viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.redeemedListId)
    }

    @Test
    fun `redeem with the server unreachable surfaces a network error`() = runTest {
        server.shutdown()
        val viewModel = newViewModel()
        viewModel.onTokenChange("abc.def")

        viewModel.redeem()?.join()

        assertNotNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.redeemedListId)
    }
}
