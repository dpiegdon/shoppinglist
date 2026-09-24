package org.p23q.shoppinglist.core.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class ErrorInterceptorTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    /** What one account's client reported, beyond the exceptions. */
    private class RecordingEvents : ApiEvents {
        var unauthorized = 0
        var outdated = 0
        var accepted = 0
        override fun onUnauthorized() { unauthorized++ }
        override fun onOutdated() { outdated++ }
        override fun onAccepted() { accepted++ }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun buildApi(tokenProvider: TokenProvider, events: ApiEvents): Api {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(ErrorInterceptor(json, events))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(Api::class.java)
    }

    private suspend fun expectFailure(block: suspend () -> Unit): ApiException {
        try {
            block()
        } catch (e: ApiException) {
            return e
        }
        fail("expected ApiException")
        error("unreachable")
    }

    @Test
    fun `401 on a request carrying a bearer token reports the token as dead`() = runTest {
        val events = RecordingEvents()
        val api = buildApi(TokenProvider { "live-token" }, events)
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error": "invalid_token", "message": "revoked"}"""),
        )

        val e = expectFailure { api.lists() }

        assert(e is UnauthorizedException)
        assertEquals(1, events.unauthorized)
    }

    @Test
    fun `401 with no bearer token (a login failure) does NOT report a dead token`() = runTest {
        val events = RecordingEvents()
        val api = buildApi(TokenProvider { null }, events)
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error": "invalid_credentials", "message": "wrong password"}"""),
        )

        val e = expectFailure { api.lists() }

        assert(e is UnauthorizedException)
        assertEquals(0, events.unauthorized)
    }

    // ---- 426: this app is too old for the server (T-244) --------------------------

    @Test
    fun `426 reports the account as outdated and still throws the server's refusal`() = runTest {
        val events = RecordingEvents()
        val api = buildApi(TokenProvider { "live-token" }, events)
        server.enqueue(
            MockResponse().setResponseCode(426)
                .setBody("""{"error": "client_outdated", "message": "too old", "protocol": 3}"""),
        )

        val e = expectFailure { api.lists() }

        // The caller still gets the server's code and status, as for any other refusal.
        assertEquals("client_outdated", e.code)
        assertEquals(426, e.httpStatus)
        assertEquals(1, events.outdated)
        // And it is not a forced logout — the session is perfectly valid.
        assertEquals(0, events.unauthorized)
    }

    @Test
    fun `an ordinary error is neither outdated nor a dead token`() = runTest {
        val events = RecordingEvents()
        val api = buildApi(TokenProvider { "live-token" }, events)
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error": "email_taken", "message": "exists"}"""),
        )

        val e = expectFailure { api.register(RegisterRequest("a@example.com", "pw")) }

        assertEquals("email_taken", e.code)
        assertEquals(0, events.outdated)
        assertEquals(0, events.unauthorized)
        assertEquals(0, events.accepted)
    }

    // ---- 2xx: the server accepts this build (T-291) -------------------------------

    @Test
    fun `a successful protocol-checked request reports the build as accepted`() = runTest {
        val events = RecordingEvents()
        val api = buildApi(TokenProvider { "live-token" }, events)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"lists": []}"""))

        api.lists()

        assertEquals(1, events.accepted)
    }

    @Test
    fun `app-version says nothing about the protocol`() = runTest {
        // Exempt from the server's protocol check, so an outdated build gets a 200 there too.
        val events = RecordingEvents()
        val api = buildApi(TokenProvider { null }, events)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"version": "1.0.0", "download_url": "https://example.com/a.apk", "protocol": 3}"""),
        )

        api.appVersion()

        assertEquals(0, events.accepted)
    }
}
