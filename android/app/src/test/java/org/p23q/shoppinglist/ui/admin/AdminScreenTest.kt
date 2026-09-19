package org.p23q.shoppinglist.ui.admin

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.SessionEvents
import org.p23q.shoppinglist.data.api.TokenProvider
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AdminScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    private val usersJson = """
        {"users":[
          {"id":"admin-1","email":"boss@example.com","created_at":1,"session_count":1,"is_admin":true},
          {"id":"user-2","email":"u@example.com","created_at":2,"session_count":0,"is_admin":false}
        ]}
    """.trimIndent()

    @Test
    fun `the user list is fetched only when asked for, then shown with its count (T-221)`() = runBlocking<Unit> {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path.endsWith("/admin/users") ->
                        MockResponse().setResponseCode(200).setBody(usersJson)
                    request.method == "GET" && path.endsWith("/admin/server-settings") ->
                        MockResponse().setResponseCode(200).setBody("""{"allow_registration":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val configFile = File.createTempFile("admin_screen_config", ".preferences_pb").apply { deleteOnExit() }
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { configFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val sessionState = FakeSessionState().apply {
            token = "tok"
            accountId = "admin-1"
            isAdmin = true
        }
        val json = Json { ignoreUnknownKeys = true }
        val viewModel = AdminViewModel(
            ApiProvider(
                serverConfig = serverConfig,
                authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
                errorInterceptor = ErrorInterceptor(json, SessionEvents()),
                json = json,
            ),
            sessionState,
        )

        composeTestRule.setContent { AdminScreen(viewModel = viewModel) }
        // Re-idle each attempt so a reply that lands late is still picked up (T-96).
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.allowRegistration != null
        }

        // The console is open and the toggle is live, but nobody has been listed.
        composeTestRule.onNodeWithText("Allow new accounts").assertExists()
        composeTestRule.onNodeWithText("u@example.com").assertDoesNotExist()
        composeTestRule.onNodeWithText("Your password (for reset/delete)").assertDoesNotExist()

        composeTestRule.onNodeWithText("Show registered users").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.users != null
        }

        composeTestRule.onNodeWithText("Registered users: 2").assertExists()
        composeTestRule.onNodeWithText("u@example.com").assertExists()
        // Shown, the list keeps the step-up field the per-user actions need, and a way to re-fetch.
        composeTestRule.onNodeWithText("Your password (for reset/delete)").assertExists()
        composeTestRule.onNodeWithText("Refresh").assertExists()

        viewModel.viewModelScope.cancel()
    }
}
