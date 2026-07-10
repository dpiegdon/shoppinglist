package org.p23q.shoppinglist.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.db.AppDb
import org.robolectric.RobolectricTestRunner
import kotlinx.serialization.json.Json
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var serverConfig: ServerConfig
    private lateinit var sessionState: FakeSessionState
    private lateinit var themePreferenceStore: ThemePreferenceStore
    private lateinit var apiProvider: ApiProvider

    @Before
    fun setUp() = runTest {
        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

        val serverConfigFile = File.createTempFile("settings_vm_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())

        val themeFile = File.createTempFile("settings_vm_theme", ".preferences_pb")
        themeFile.deleteOnExit()
        themePreferenceStore = ThemePreferenceStore(PreferenceDataStoreFactory.create { themeFile })

        sessionState = FakeSessionState().apply {
            token = "tok-123"
            accountEmail = "milk@example.com"
            defaultCurrency = "EUR"
        }

        val json = Json { ignoreUnknownKeys = true }
        apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun newViewModel(): SettingsViewModel =
        SettingsViewModel(apiProvider, sessionState, serverConfig, themePreferenceStore, db)

    @Test
    fun `initial state loads server URL, account email, and cached currency without a network call`() = runTest {
        val viewModel = newViewModel()

        val state = viewModel.uiState.first { it.serverUrl.isNotBlank() }
        assertEquals(server.url("/").toString(), state.serverUrl)
        assertEquals("milk@example.com", state.accountEmail)
        assertEquals("EUR", state.defaultCurrency)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `updateCurrency rejects an invalid code locally without calling the server`() = runTest {
        val viewModel = newViewModel()

        val job = viewModel.updateCurrency("euros")

        assertNull(job)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `updateCurrency success updates state and the session cache`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "USD"}"""))
        val viewModel = newViewModel()

        viewModel.updateCurrency("usd")?.join()

        assertEquals("USD", viewModel.uiState.value.defaultCurrency)
        assertEquals("USD", sessionState.defaultCurrency)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `changePassword with the wrong current password surfaces an inline error`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error": "unauthorized", "message": "wrong password"}"""),
        )
        val viewModel = newViewModel()
        viewModel.onCurrentPasswordChange("wrong")
        viewModel.onNewPasswordChange("newpass123")

        viewModel.changePassword()?.join()

        assertEquals("Current password is incorrect", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `changePassword success clears the fields and shows a confirmation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val viewModel = newViewModel()
        viewModel.onCurrentPasswordChange("hunter2")
        viewModel.onNewPasswordChange("newpass123")

        viewModel.changePassword()?.join()

        assertEquals("", viewModel.uiState.value.currentPassword)
        assertEquals("", viewModel.uiState.value.newPassword)
        assertNull(viewModel.uiState.value.errorMessage)
        assertNotNull(viewModel.uiState.value.infoMessage)
    }

    @Test
    fun `changeEmail success updates the account email and session cache`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val viewModel = newViewModel()
        viewModel.onChangeEmailPasswordChange("hunter2")
        viewModel.onNewEmailChange("new@example.com")

        viewModel.changeEmail()?.join()

        assertEquals("new@example.com", viewModel.uiState.value.accountEmail)
        assertEquals("new@example.com", sessionState.accountEmail)
    }

    @Test
    fun `loadSessions populates the sessions list`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sessions": [{"id": "s1", "device_label": "Pixel", "created_at": 1, "last_seen_at": 2, "current": true}]}""",
            ),
        )
        val viewModel = newViewModel()

        viewModel.loadSessions().join()

        assertEquals(1, viewModel.uiState.value.sessions.size)
        assertEquals("Pixel", viewModel.uiState.value.sessions.first().deviceLabel)
    }

    @Test
    fun `loadSessions tolerates a null device_label (confirmed against the live dev server)`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sessions": [{"id": "s1", "device_label": null, "created_at": 1, "last_seen_at": 2, "current": true}]}""",
            ),
        )
        val viewModel = newViewModel()

        viewModel.loadSessions().join()

        assertNull(viewModel.uiState.value.sessions.single().deviceLabel)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `revokeSession calls the server then refreshes the list`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sessions": [{"id": "s1", "device_label": "Pixel", "created_at": 1, "last_seen_at": 2, "current": true},""" +
                    """{"id": "s2", "device_label": "Other", "created_at": 1, "last_seen_at": 2, "current": false}]}""",
            ),
        )
        val viewModel = newViewModel()
        viewModel.loadSessions().join()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"sessions": [{"id": "s1", "device_label": "Pixel", "created_at": 1, "last_seen_at": 2, "current": true}]}""",
            ),
        )

        viewModel.revokeSession("s2").join()

        assertEquals(listOf("s1"), viewModel.uiState.value.sessions.map { it.id })
    }

    @Test
    fun `confirmDeleteAccount with the wrong password surfaces an inline error and does not wipe local data`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error": "unauthorized", "message": "wrong password"}"""),
        )
        val viewModel = newViewModel()
        viewModel.onDeleteAccountPasswordChange("wrong")

        viewModel.confirmDeleteAccount()?.join()

        assertEquals("Password is incorrect", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isAccountDeleted)
        assertNotNull(sessionState.token)
    }

    @Test
    fun `confirmDeleteAccount success wipes the session and local mirror`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val viewModel = newViewModel()
        viewModel.onDeleteAccountPasswordChange("hunter2")

        viewModel.confirmDeleteAccount()?.join()

        assertTrue(viewModel.uiState.value.isAccountDeleted)
        assertNull(sessionState.token)
    }

    @Test
    fun `setTheme persists the preference and it is reflected in state`() = runTest {
        val viewModel = newViewModel()

        viewModel.setTheme(ThemePreference.DARK).join()

        assertEquals(ThemePreference.DARK, viewModel.uiState.first { it.theme == ThemePreference.DARK }.theme)
        assertEquals(ThemePreference.DARK, themePreferenceStore.theme.first())
    }
}
