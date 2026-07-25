package org.p23q.shoppinglist.ui.admin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
import org.p23q.shoppinglist.data.api.SessionEvents
import org.p23q.shoppinglist.data.api.TokenProvider
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AdminViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var apiProvider: ApiProvider
    private lateinit var sessionState: FakeSessionState

    private val usersJson = """
        {"users":[
          {"id":"admin-1","email":"boss@example.com","created_at":1,"session_count":1,"is_admin":true},
          {"id":"user-2","email":"u@example.com","created_at":2,"session_count":0,"is_admin":false}
        ]}
    """.trimIndent()

    @Before
    fun setUp() = runTest(mainDispatcherRule.dispatcher) {
        server = MockWebServer()
        server.start()
        val configFile = File.createTempFile("admin_vm_config", ".preferences_pb")
        configFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { configFile })
        serverConfig.setServerUrl(server.url("/").toString())
        sessionState = FakeSessionState().apply {
            token = "tok"
            accountId = "admin-1"
            isAdmin = true
        }
        val json = Json { ignoreUnknownKeys = true }
        apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, SessionEvents()),
            json = json,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    /** Routes by method + path so init's two GETs and later PUT/DELETE each get the right response. */
    private fun route(registrationAfterPut: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path.endsWith("/admin/users") ->
                        MockResponse().setResponseCode(200).setBody(usersJson)
                    request.method == "GET" && path.endsWith("/admin/server-settings") ->
                        MockResponse().setResponseCode(200).setBody("""{"allow_registration":true}""")
                    request.method == "PUT" && path.endsWith("/admin/server-settings") ->
                        MockResponse().setResponseCode(200)
                            .setBody("""{"allow_registration":$registrationAfterPut}""")
                    request.method == "DELETE" && path.contains("/admin/users/") ->
                        MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun newViewModel() = AdminViewModel(apiProvider, sessionState)

    @Test
    fun `load populates users and the registration flag`() = runTest(mainDispatcherRule.dispatcher) {
        route()
        val viewModel = newViewModel()

        val state = viewModel.uiState.first { it.users.isNotEmpty() }
        assertEquals(2, state.users.size)
        assertTrue(state.allowRegistration == true)
    }

    @Test
    fun `toggleRegistration flips the flag`() = runTest(mainDispatcherRule.dispatcher) {
        route(registrationAfterPut = false)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.allowRegistration != null }

        viewModel.toggleRegistration()?.join()

        assertFalse(viewModel.uiState.value.allowRegistration!!)
    }

    @Test
    fun `a missing password is reported at the field, not silently ignored (T-113)`() =
        runTest(mainDispatcherRule.dispatcher) {
            route()
            val viewModel = newViewModel()
            val loaded = viewModel.uiState.first { it.users.isNotEmpty() }
            val victim = loaded.users.first { it.id == "user-2" }

            // requirePassword is what the screen calls before opening the delete confirmation.
            assertFalse(viewModel.requirePassword())
            assertEquals(
                "Enter your password to reset or delete a user.",
                viewModel.uiState.value.passwordError,
            )

            // Reset reports it the same way, and doesn't run.
            assertEquals(null, viewModel.resetPassword(victim))
            assertTrue(viewModel.uiState.value.passwordError != null)

            // Typing a password clears the complaint and unblocks the action.
            viewModel.onPasswordChange("adminpw")
            assertEquals(null, viewModel.uiState.value.passwordError)
            assertTrue(viewModel.requirePassword())
        }

    @Test
    fun `deleteUser requires the step-up password and removes the row`() = runTest(mainDispatcherRule.dispatcher) {
        route()
        val viewModel = newViewModel()
        val loaded = viewModel.uiState.first { it.users.isNotEmpty() }
        val victim = loaded.users.first { it.id == "user-2" }

        // No password yet → guarded, nothing removed.
        assertEquals(null, viewModel.deleteUser(victim))
        assertTrue(viewModel.uiState.value.users.any { it.id == "user-2" })

        viewModel.onPasswordChange("adminpw")
        viewModel.deleteUser(victim)?.join()

        assertFalse(viewModel.uiState.value.users.any { it.id == "user-2" })
    }
}
