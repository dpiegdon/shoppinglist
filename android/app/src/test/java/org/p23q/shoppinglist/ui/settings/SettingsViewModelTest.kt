package org.p23q.shoppinglist.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import org.p23q.shoppinglist.data.DefaultCurrencyState
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.crash.CrashLogWriter
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
    private lateinit var crashLogWriter: CrashLogWriter
    private lateinit var defaultCurrencyState: DefaultCurrencyState
    private lateinit var notificationPrefs: NotificationPrefsStore

    @Before
    fun setUp() = runTest(mainDispatcherRule.dispatcher) {
        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(mainDispatcherRule.dispatcher)
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

        val crashLogFile = File.createTempFile("settings_vm_crash_log", ".txt")
        crashLogFile.deleteOnExit()
        crashLogWriter = CrashLogWriter(crashLogFile)

        defaultCurrencyState = DefaultCurrencyState(sessionState)

        val notifPrefsFile = File.createTempFile("settings_vm_notif_prefs", ".preferences_pb")
        notifPrefsFile.deleteOnExit()
        notificationPrefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { notifPrefsFile })
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun newViewModel(): SettingsViewModel =
        SettingsViewModel(
            apiProvider, sessionState, serverConfig, themePreferenceStore, db,
            crashLogWriter, defaultCurrencyState, notificationPrefs,
        )

    @Test
    fun `initial state loads server URL, account email, and cached currency without a network call`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        val state = viewModel.uiState.first { it.serverUrl.isNotBlank() }
        assertEquals(server.url("/").toString(), state.serverUrl)
        assertEquals("milk@example.com", state.accountEmail)
        assertEquals("EUR", state.defaultCurrency)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `updateCurrency rejects an invalid code locally without calling the server`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        val job = viewModel.updateCurrency("euros")

        assertNull(job)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `updateCurrency success updates state and the session cache`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "USD", "initials": "MI"}"""))
        val viewModel = newViewModel()

        viewModel.updateCurrency("usd")?.join()

        assertEquals("USD", viewModel.uiState.value.defaultCurrency)
        assertEquals("USD", sessionState.defaultCurrency)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `updateCurrency success also writes through the in-memory mirror (T-55)`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "USD", "initials": "MI"}"""))
        val viewModel = newViewModel()

        viewModel.updateCurrency("usd")?.join()

        assertEquals("USD", defaultCurrencyState.currency.value)
    }

    @Test
    fun `updateCurrency never sends initials, and leaves the loaded value on screen (T-103)`() = runTest(mainDispatcherRule.dispatcher) {
        // GET and PATCH share the same /settings path, and either can arrive more than once (e.g.
        // OkHttp's silent retry-on-connection-failure) — a FIFO .enqueue() queue can't guarantee
        // which physical request gets which response, so route by method instead (same pattern as
        // SettingsScreenTest).
        val recordedRequests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return when (request.method) {
                    "PATCH" -> MockResponse().setResponseCode(200).setBody("""{"default_currency": "USD", "initials": "XY"}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "XY"}""")
                }
            }
        }
        val viewModel = newViewModel()
        viewModel.loadInitials().join()

        viewModel.updateCurrency("usd")?.join()

        // The key must be absent, not echoed back: the server leaves an absent key unchanged
        // (T-87). Resending the loaded value pinned an email-derived default as a real override
        // (T-103) — this test previously asserted the opposite, which was the bug.
        val patchRequest = recordedRequests.first { it.method == "PATCH" }
        assertFalse(patchRequest.body.readUtf8().contains("initials"))
        // Still displayed afterwards — omitting it from the write must not blank the field.
        assertEquals("XY", viewModel.uiState.value.initials)
    }

    @Test
    fun `updateCurrency does not pin the email-derived default as an override (T-103)`() = runTest(mainDispatcherRule.dispatcher) {
        // GET /settings returns the *resolved* value, so an account with no override reads back
        // the derived default ("BO" for bob@…) looking exactly like a stored one. Echoing it back
        // on a currency save stored it for real, so a later email change no longer re-derived it.
        val recordedRequests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return when (request.method) {
                    "PATCH" -> MockResponse().setResponseCode(200).setBody("""{"default_currency": "USD", "initials": "BO"}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "BO"}""")
                }
            }
        }
        val viewModel = newViewModel()
        viewModel.loadInitials().join()
        assertEquals("BO", viewModel.uiState.value.initials)

        viewModel.updateCurrency("usd")?.join()

        val patchRequest = recordedRequests.first { it.method == "PATCH" }
        assertFalse(patchRequest.body.readUtf8().contains("initials"))
    }

    // A standalone "loadInitials populates state" test was removed here: its exact claim (a fresh
    // fetch lands in uiState.initials) is already asserted by the test above, which calls
    // loadInitials().join() itself and checks the resulting value — no unique coverage lost.

    @Test
    fun `updateCurrency omits the initials key when the preload has not resolved (T-97)`() = runTest(mainDispatcherRule.dispatcher) {
        // loadInitials() is deliberately never called — simulates an offline start racing the
        // best-effort preload. Before T-97, uiState.initials started at literal "" and got resent
        // as a real value, clobbering any custom override server-side; it must now be omitted.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "USD", "initials": ""}"""))
        val viewModel = newViewModel()

        viewModel.updateCurrency("usd")?.join()

        val request = server.takeRequest()
        assertFalse(request.body.readUtf8().contains("initials"))
    }

    @Test
    fun `an explicit initials save sends it, and a later currency save still does not (T-103)`() = runTest(mainDispatcherRule.dispatcher) {
        val recordedRequests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedRequests.add(request)
                return when (request.method) {
                    "PATCH" -> if (recordedRequests.count { it.method == "PATCH" } == 1) {
                        MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "ZZ"}""")
                    } else {
                        MockResponse().setResponseCode(200).setBody("""{"default_currency": "USD", "initials": "ZZ"}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "ZZ"}""")
                }
            }
        }
        val viewModel = newViewModel()

        viewModel.updateInitials("zz")?.join()
        viewModel.updateCurrency("usd")?.join()

        val patchRequests = recordedRequests.filter { it.method == "PATCH" }
        assertEquals(2, patchRequests.size)
        // The initials form sends it — that's a deliberate user action on that field.
        assertTrue(patchRequests[0].body.readUtf8().contains("\"initials\":\"ZZ\""))
        // The currency form does not, even though the value is now known (T-103). The override
        // survives because the server leaves an absent key unchanged, not because we re-send it.
        assertFalse(patchRequests[1].body.readUtf8().contains("initials"))
    }

    @Test
    fun `updateInitials rejects more than 3 characters locally without calling the server`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        val job = viewModel.updateInitials("TooLong")

        assertNull(job)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `updateInitials resends the current currency so it is not overwritten (T-64)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()  // defaultCurrency = "EUR" from FakeSessionState in setUp
        // Await the init load (currency comes from a DataStore read on Dispatchers.IO) before acting:
        // updateInitials resends _uiState.value.defaultCurrency, so the test must not race that load.
        viewModel.uiState.first { it.defaultCurrency == "EUR" }

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "AB"}"""))
        viewModel.updateInitials("ab")?.join()

        val request = server.takeRequest()
        assertTrue(request.body.readUtf8().contains("\"default_currency\":\"EUR\""))
        assertEquals("AB", viewModel.uiState.value.initials)
    }

    @Test
    fun `changePassword with the wrong current password surfaces an inline error`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `changePassword success clears the fields and shows a confirmation`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `changeEmail success updates the account email and session cache`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(204))
        val viewModel = newViewModel()
        viewModel.onChangeEmailPasswordChange("hunter2")
        viewModel.onNewEmailChange("new@example.com")

        viewModel.changeEmail()?.join()

        assertEquals("new@example.com", viewModel.uiState.value.accountEmail)
        assertEquals("new@example.com", sessionState.accountEmail)
    }

    @Test
    fun `loadSessions populates the sessions list`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `loadSessions tolerates a null device_label (confirmed against the live dev server)`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `revokeSession calls the server then refreshes the list`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `confirmDeleteAccount with the wrong password surfaces an inline error and does not wipe local data`() = runTest(mainDispatcherRule.dispatcher) {
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
    fun `confirmDeleteAccount success wipes the session and local mirror`() = runTest(mainDispatcherRule.dispatcher) {
        server.enqueue(MockResponse().setResponseCode(204))
        val viewModel = newViewModel()
        viewModel.onDeleteAccountPasswordChange("hunter2")

        viewModel.confirmDeleteAccount()?.join()

        assertTrue(viewModel.uiState.value.isAccountDeleted)
        assertNull(sessionState.token)
    }

    @Test
    fun `setTheme persists the preference and it is reflected in state`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        viewModel.setTheme(ThemePreference.DARK).join()

        assertEquals(ThemePreference.DARK, viewModel.uiState.first { it.theme == ThemePreference.DARK }.theme)
        assertEquals(ThemePreference.DARK, themePreferenceStore.theme.first())
    }

    @Test
    fun `allowSelfSignedCerts loads from and persists to server config`() = runTest(mainDispatcherRule.dispatcher) {
        serverConfig.setAllowSelfSignedCerts(true)
        val viewModel = newViewModel()
        assertTrue(viewModel.uiState.first { it.allowSelfSignedCerts }.allowSelfSignedCerts)

        viewModel.setAllowSelfSignedCerts(false).join()

        assertFalse(viewModel.uiState.value.allowSelfSignedCerts)
        assertFalse(serverConfig.allowSelfSignedCerts.first())
    }

    @Test
    fun `notification toggle state loads from prefs and updates live (T-65)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        assertTrue(viewModel.uiState.first { it.notificationsEnabled }.notificationsEnabled)

        viewModel.setNotificationsEnabled(false).join()

        assertFalse(viewModel.uiState.first { !it.notificationsEnabled }.notificationsEnabled)
        assertFalse(notificationPrefs.notificationsEnabled.first())
    }

    @Test
    fun `shareLogs with no crash log yet surfaces a message instead of a path (T-50)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        viewModel.shareLogs()

        assertNull(viewModel.uiState.value.crashLogPath)
        assertEquals("No crash logs yet", viewModel.uiState.value.infoMessage)
    }

    @Test
    fun `shareLogs with an existing log exposes its path, and consumeCrashLogShare clears it (T-50)`() = runTest(mainDispatcherRule.dispatcher) {
        crashLogWriter.append("main", RuntimeException("boom"))
        val viewModel = newViewModel()

        viewModel.shareLogs()

        assertEquals(crashLogWriter.logFile.absolutePath, viewModel.uiState.value.crashLogPath)

        viewModel.consumeCrashLogShare()

        assertNull(viewModel.uiState.value.crashLogPath)
    }
}
