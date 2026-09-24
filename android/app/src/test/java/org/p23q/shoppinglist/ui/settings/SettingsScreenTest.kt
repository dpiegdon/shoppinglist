package org.p23q.shoppinglist.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.DefaultCurrencyState
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.core.AppLocale
import org.p23q.shoppinglist.data.crash.CrashLogWriter
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.LocalizedContent
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun `renders account info and delete account opens a password-confirm dialog`() = runBlocking {
        server = MockWebServer()
        // loadSessions() and loadInitials() (T-64) both fire from LaunchedEffect(Unit) on mount,
        // racing each other — a FIFO .enqueue() queue can't guarantee which gets which response.
        // Route by path instead so each endpoint gets the response it actually expects.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/account/sessions") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"sessions": []}""")
                request.path?.endsWith("/settings") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}""")
                request.method == "DELETE" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val serverConfigFile = File.createTempFile("settings_screen_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val themeFile = File.createTempFile("settings_screen_theme", ".preferences_pb")
        themeFile.deleteOnExit()
        val themePreferenceStore = ThemePreferenceStore(PreferenceDataStoreFactory.create { themeFile })
        val sessionState = FakeSessionState().apply {
            token = "tok-123"
            accountEmail = "milk@example.com"
            defaultCurrency = "EUR"
        }
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
        val crashLogFile = File.createTempFile("settings_screen_crash_log", ".txt")
        crashLogFile.deleteOnExit()
        val crashLogWriter = CrashLogWriter(crashLogFile)
        val viewModel = SettingsViewModel(
            apiProvider,
            sessionState,
            serverConfig,
            themePreferenceStore,
            db,
            crashLogWriter,
            DefaultCurrencyState(sessionState),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("settings_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )
        var deleted = false

        composeTestRule.setContent {
            SettingsScreen(onAccountDeleted = { deleted = true }, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("milk@example.com").assertExists()

        composeTestRule.onNodeWithText("Delete account").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Confirm password").assertExists()

        db.close()
        assertEquals(false, deleted)
    }

    @Test
    fun `initials load from the server and can be edited and saved (T-64)`() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/account/sessions") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"sessions": []}""")
                request.method == "PATCH" ->
                    MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "XY"}""")
                request.path?.endsWith("/settings") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val serverConfigFile = File.createTempFile("settings_screen_initials_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val themeFile = File.createTempFile("settings_screen_initials_theme", ".preferences_pb")
        themeFile.deleteOnExit()
        val themePreferenceStore = ThemePreferenceStore(PreferenceDataStoreFactory.create { themeFile })
        val sessionState = FakeSessionState().apply {
            token = "tok-123"
            accountEmail = "milk@example.com"
            defaultCurrency = "EUR"
        }
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
        val crashLogFile = File.createTempFile("settings_screen_crash_log", ".txt")
        crashLogFile.deleteOnExit()
        val crashLogWriter = CrashLogWriter(crashLogFile)
        val viewModel = SettingsViewModel(
            apiProvider,
            sessionState,
            serverConfig,
            themePreferenceStore,
            db,
            crashLogWriter,
            DefaultCurrencyState(sessionState),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("settings_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )

        composeTestRule.setContent { SettingsScreen(onAccountDeleted = {}, viewModel = viewModel) }
        // loadInitials() (mounted via LaunchedEffect(Unit)) fires a real MockWebServer round trip
        // on OkHttp's dispatcher, which waitForIdle() alone doesn't wait for (T-96) - poll the
        // observed state instead, re-idling Compose each attempt so a response that lands late is
        // still picked up.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.initials == "MI"
        }

        composeTestRule.onNodeWithText("MI").assertExists()

        composeTestRule.onNodeWithText("MI").performScrollTo().performTextReplacement("XY")
        composeTestRule.onAllNodesWithText("Save")[1].performClick()
        // Same race on the PATCH response for the save itself (T-96 root cause).
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.initials == "XY"
        }

        assertEquals("XY", viewModel.uiState.value.initials)
        db.close()
    }

    @Test
    fun `tapping Share crash logs with no log yet surfaces a message instead of a broken share sheet (T-50)`() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/account/sessions") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"sessions": []}""")
                request.path?.endsWith("/settings") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val serverConfigFile = File.createTempFile("settings_screen_crashlog_server_config", ".preferences_pb")
        serverConfigFile.deleteOnExit()
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { serverConfigFile })
        serverConfig.setServerUrl(server.url("/").toString())
        val themeFile = File.createTempFile("settings_screen_crashlog_theme", ".preferences_pb")
        themeFile.deleteOnExit()
        val themePreferenceStore = ThemePreferenceStore(PreferenceDataStoreFactory.create { themeFile })
        val sessionState = FakeSessionState().apply {
            token = "tok-123"
            accountEmail = "milk@example.com"
            defaultCurrency = "EUR"
        }
        val json = Json { ignoreUnknownKeys = true }
        val apiProvider = ApiProvider(
            serverConfig = serverConfig,
            authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
            errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
            json = json,
        )
        val crashLogFile = File.createTempFile("settings_screen_crashlog_empty", ".txt")
        crashLogFile.deleteOnExit()
        val crashLogWriter = CrashLogWriter(crashLogFile)
        val viewModel = SettingsViewModel(
            apiProvider,
            sessionState,
            serverConfig,
            themePreferenceStore,
            db,
            crashLogWriter,
            DefaultCurrencyState(sessionState),
            NotificationPrefsStore(
                PreferenceDataStoreFactory.create {
                    File.createTempFile("settings_screen_notif_prefs", ".preferences_pb").apply { deleteOnExit() }
                },
            ),
        )

        composeTestRule.setContent { SettingsScreen(onAccountDeleted = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Share crash logs").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("No crash logs yet").assertExists()
        assertEquals(null, viewModel.uiState.value.crashLogPath)
        db.close()
    }

    @Test
    fun `an admin is offered no server console here — it is a main-menu entry now (T-220)`() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/account/sessions") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"sessions": []}""")
                request.path?.endsWith("/settings") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        fun prefsFile(name: String) = File.createTempFile(name, ".preferences_pb").apply { deleteOnExit() }
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { prefsFile("settings_admin_server_config") })
        serverConfig.setServerUrl(server.url("/").toString())
        // An admin account: before T-220 this is exactly who got the button on this screen.
        val sessionState = FakeSessionState().apply {
            token = "tok-123"
            accountEmail = "boss@example.com"
            defaultCurrency = "EUR"
            isAdmin = true
        }
        val json = Json { ignoreUnknownKeys = true }
        val viewModel = SettingsViewModel(
            ApiProvider(
                serverConfig = serverConfig,
                authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
                errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
                json = json,
            ),
            sessionState,
            serverConfig,
            ThemePreferenceStore(PreferenceDataStoreFactory.create { prefsFile("settings_admin_theme") }),
            db,
            CrashLogWriter(File.createTempFile("settings_admin_crashlog", ".txt").apply { deleteOnExit() }),
            DefaultCurrencyState(sessionState),
            NotificationPrefsStore(PreferenceDataStoreFactory.create { prefsFile("settings_admin_notif") }),
        )

        composeTestRule.setContent { SettingsScreen(onAccountDeleted = {}, viewModel = viewModel) }
        // Let the screen's own loads finish inside the test, re-idling each attempt so a reply that
        // lands late is still picked up (T-96); cancelling below then keeps nothing running on Main.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.initials == "MI"
        }

        composeTestRule.onNodeWithText("boss@example.com").assertExists()
        composeTestRule.onNodeWithText("Server admin").assertDoesNotExist()

        viewModel.viewModelScope.cancel()
        db.close()
    }

    @Test
    fun `settings keeps account preferences only — no version, no update controls (T-224)`() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/account/sessions") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"sessions": []}""")
                request.path?.endsWith("/settings") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        fun prefsFile(name: String) = File.createTempFile(name, ".preferences_pb").apply { deleteOnExit() }
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { prefsFile("settings_about_server_config") })
        serverConfig.setServerUrl(server.url("/").toString())
        val sessionState = FakeSessionState().apply {
            token = "tok-123"
            accountEmail = "milk@example.com"
            defaultCurrency = "EUR"
        }
        val json = Json { ignoreUnknownKeys = true }
        val viewModel = SettingsViewModel(
            ApiProvider(
                serverConfig = serverConfig,
                authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
                errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
                json = json,
            ),
            sessionState,
            serverConfig,
            ThemePreferenceStore(PreferenceDataStoreFactory.create { prefsFile("settings_about_theme") }),
            db,
            CrashLogWriter(File.createTempFile("settings_about_crashlog", ".txt").apply { deleteOnExit() }),
            DefaultCurrencyState(sessionState),
            NotificationPrefsStore(PreferenceDataStoreFactory.create { prefsFile("settings_about_notif") }),
        )

        composeTestRule.setContent { SettingsScreen(onAccountDeleted = {}, viewModel = viewModel) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.initials == "MI"
        }

        // Still a settings screen: the account preferences are all here.
        composeTestRule.onNodeWithText("Notifications").performScrollTo().assertExists()
        // The whole App-updates block and the version line moved to About.
        composeTestRule.onNodeWithText("App updates").assertDoesNotExist()
        composeTestRule.onNodeWithText("Check for updates automatically").assertDoesNotExist()
        composeTestRule.onAllNodesWithText("Version", substring = true).assertCountEquals(0)

        viewModel.viewModelScope.cancel()
        db.close()
    }

    @Test
    fun `the current session's device label is translated, not hard-coded English (T-270)`() = runBlocking {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/account/sessions") == true ->
                    MockResponse().setResponseCode(200).setBody(
                        """{"sessions": [{"id": "s1", "device_label": "Pixel", "created_at": 0, "last_seen_at": 0, "current": true}]}""",
                    )
                request.path?.endsWith("/settings") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        fun prefsFile(name: String) = File.createTempFile(name, ".preferences_pb").apply { deleteOnExit() }
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { prefsFile("settings_device_server_config") })
        serverConfig.setServerUrl(server.url("/").toString())
        val sessionState = FakeSessionState().apply {
            token = "tok-123"
            accountEmail = "milk@example.com"
            defaultCurrency = "EUR"
        }
        val json = Json { ignoreUnknownKeys = true }
        val viewModel = SettingsViewModel(
            ApiProvider(
                serverConfig = serverConfig,
                authInterceptor = AuthInterceptor(TokenProvider { sessionState.token }),
                errorInterceptor = ErrorInterceptor(json, org.p23q.shoppinglist.data.api.SessionEvents()),
                json = json,
            ),
            sessionState,
            serverConfig,
            ThemePreferenceStore(PreferenceDataStoreFactory.create { prefsFile("settings_device_theme") }),
            db,
            CrashLogWriter(File.createTempFile("settings_device_crashlog", ".txt").apply { deleteOnExit() }),
            DefaultCurrencyState(sessionState),
            NotificationPrefsStore(PreferenceDataStoreFactory.create { prefsFile("settings_device_notif") }),
        )

        // Renders under German (T-111's LocalizedContent) rather than relying on the device
        // locale: a hard-coded literal reads as English regardless, which is exactly the defect.
        composeTestRule.setContent {
            LocalizedContent(AppLocale.GERMAN) {
                SettingsScreen(onAccountDeleted = {}, viewModel = viewModel)
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.sessions.isNotEmpty()
        }

        composeTestRule.onNodeWithText("Pixel (dieses Gerät)").assertExists()

        viewModel.viewModelScope.cancel()
        db.close()
    }
}
