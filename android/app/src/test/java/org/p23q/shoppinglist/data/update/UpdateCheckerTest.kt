package org.p23q.shoppinglist.data.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AuthInterceptor
import org.p23q.shoppinglist.data.api.ErrorInterceptor
import org.p23q.shoppinglist.data.api.SessionEvents
import org.p23q.shoppinglist.data.api.TokenProvider
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * T-135. Driven through a real ApiProvider against MockWebServer rather than a hand-rolled fake
 * Api, following the existing repo/viewmodel tests: the 404 path in particular only behaves
 * realistically if it goes through ErrorInterceptor, which is what turns a non-2xx into the
 * ApiException this class relies on catching.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: UpdatePrefsStore
    private lateinit var serverConfig: ServerConfig
    private lateinit var checker: UpdateChecker

    @Before
    fun setUp() = runTest {
        server = MockWebServer()
        server.start()

        val prefsFile = File.createTempFile("update_prefs_test", ".preferences_pb")
        prefsFile.deleteOnExit()
        prefs = UpdatePrefsStore(PreferenceDataStoreFactory.create { prefsFile })

        val configFile = File.createTempFile("update_server_config", ".preferences_pb")
        configFile.deleteOnExit()
        serverConfig = ServerConfig(PreferenceDataStoreFactory.create { configFile })
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
        server.shutdown()
    }

    private fun offering(version: String) = MockResponse()
        .setResponseCode(200)
        .setBody("""{"version": "$version", "download_url": "https://example.com/shoppinglist.apk"}""")

    @Test
    fun `offers a newer version`() = runTest {
        server.enqueue(offering("1.12.0"))

        val update = checker.check(currentVersion = "1.11.0")

        assertNotNull(update)
        assertEquals("1.12.0", update!!.version)
        assertEquals("https://example.com/shoppinglist.apk", update.downloadUrl)
    }

    @Test
    fun `a two-digit minor really is newer`() = runTest {
        // The version pair a string comparison gets backwards, asserted end-to-end and not only
        // in the comparator's own test.
        server.enqueue(offering("1.10.0"))

        assertNotNull(checker.check(currentVersion = "1.9.3"))
    }

    @Test
    fun `says nothing when the server matches or trails this build`() = runTest {
        server.enqueue(offering("1.12.0"))
        assertNull(checker.check(currentVersion = "1.12.0"))

        prefs.recordCheck(0L)
        server.enqueue(offering("1.11.0"))
        assertNull(checker.check(currentVersion = "1.12.0"))
    }

    @Test
    fun `asks about a version only once`() = runTest {
        server.enqueue(offering("1.12.0"))
        val first = checker.check(currentVersion = "1.11.0")
        assertNotNull(first)
        checker.markPrompted(first!!.version)

        prefs.recordCheck(0L)
        server.enqueue(offering("1.12.0"))

        assertNull(checker.check(currentVersion = "1.11.0"))
    }

    @Test
    fun `a skipped version does not suppress the next one`() = runTest {
        checker.markPrompted("1.12.0")
        server.enqueue(offering("1.13.0"))

        assertNotNull(checker.check(currentVersion = "1.11.0"))
    }

    @Test
    fun `a server too old to answer is silent, not an error`() = runTest {
        // What every server released before this endpoint returns.
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "not_found", "message": "nope"}"""))

        assertNull(checker.check(currentVersion = "1.11.0"))
    }

    @Test
    fun `an unparseable version is declined rather than guessed at`() = runTest {
        server.enqueue(offering("2.0.0-rc1"))

        assertNull(checker.check(currentVersion = "1.11.0"))
    }

    @Test
    fun `a dead server is silent`() = runTest {
        server.shutdown()

        assertNull(checker.check(currentVersion = "1.11.0"))
    }

    @Test
    fun `switching the check off sends no request at all`() = runTest {
        prefs.setAutoCheckEnabled(false)
        server.enqueue(offering("1.12.0"))

        assertNull(checker.check(currentVersion = "1.11.0"))
        // The point of the toggle: not a silent check, no check. If a request had gone out, the
        // enqueued response would have been consumed and this count would be 1.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `no server configured means no request`() = runTest {
        val emptyConfigFile = File.createTempFile("update_no_server", ".preferences_pb")
        emptyConfigFile.deleteOnExit()
        val json = Json { ignoreUnknownKeys = true }
        val unconfigured = UpdateChecker(
            apiProvider = ApiProvider(
                serverConfig = ServerConfig(PreferenceDataStoreFactory.create { emptyConfigFile }),
                authInterceptor = AuthInterceptor(TokenProvider { null }),
                errorInterceptor = ErrorInterceptor(json, SessionEvents()),
                json = json,
            ),
            serverConfig = ServerConfig(PreferenceDataStoreFactory.create { emptyConfigFile }),
            prefs = prefs,
        )

        assertNull(unconfigured.check(currentVersion = "1.11.0"))
    }

    @Test
    fun `a second check inside the rate limit window does not hit the server`() = runTest {
        server.enqueue(offering("1.12.0"))
        assertNotNull(checker.check(currentVersion = "1.11.0"))
        val afterFirst = server.requestCount

        server.enqueue(offering("1.12.0"))
        checker.check(currentVersion = "1.11.0")

        assertEquals(afterFirst, server.requestCount)
    }

    @Test
    fun `the attempt is recorded before the request, so a dead server is not retried every time`() = runTest {
        server.shutdown()

        assertNull(checker.check(currentVersion = "1.11.0"))

        // Recording only on success would leave lastCheckedAt at 0 and re-hit an unreachable
        // server on every single foreground.
        assertEquals(true, prefs.lastCheckedAt.first() > 0L)
    }
}
