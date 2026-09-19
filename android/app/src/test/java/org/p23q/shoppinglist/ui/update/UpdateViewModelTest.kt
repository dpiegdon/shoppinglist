package org.p23q.shoppinglist.ui.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.SessionEvents
import org.p23q.shoppinglist.data.api.TokenProvider
import org.p23q.shoppinglist.data.update.UpdateChecker
import org.p23q.shoppinglist.data.update.UpdatePrefsStore
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The "check automatically" switch, which moved here from SettingsViewModel with the block it
 * drives (T-224). The check itself is covered by UpdateCheckerTest.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var prefs: UpdatePrefsStore
    private lateinit var checker: UpdateChecker

    @Before
    fun setUp() = runTest(mainDispatcherRule.dispatcher) {
        server = MockWebServer()
        server.start()

        fun prefsFile(name: String) = File.createTempFile(name, ".preferences_pb").apply { deleteOnExit() }
        prefs = UpdatePrefsStore(PreferenceDataStoreFactory.create { prefsFile("update_vm_prefs") })
        val serverConfig = ServerConfig(PreferenceDataStoreFactory.create { prefsFile("update_vm_server_config") })
        serverConfig.setServerUrl(server.url("/").toString())

        val json = Json { ignoreUnknownKeys = true }
        checker = UpdateChecker(
            apiProvider = ApiProvider(
                serverConfig = serverConfig,
                authInterceptor = AuthInterceptor(TokenProvider { null }),
                errorInterceptor = ErrorInterceptor(json, SessionEvents()),
                json = json,
            ),
            serverConfig = serverConfig,
            prefs = prefs,
        )
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun `the automatic update check defaults on and the toggle persists it (T-135)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = UpdateViewModel(checker, prefs)
            // Defaults on: a self-hosted app has no store to nag you, so off-by-default would mean
            // never hearing about a release at all.
            assertTrue(viewModel.autoCheckEnabled.first { it })

            viewModel.setAutoCheckEnabled(false).join()

            assertFalse(viewModel.autoCheckEnabled.first { !it })
            assertFalse(prefs.autoCheckEnabled.first())
        }
}
