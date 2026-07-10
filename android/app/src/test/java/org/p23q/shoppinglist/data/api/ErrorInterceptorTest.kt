package org.p23q.shoppinglist.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

@OptIn(ExperimentalCoroutinesApi::class)
class ErrorInterceptorTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildApi(tokenProvider: TokenProvider, sessionEvents: SessionEvents): Api {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(ErrorInterceptor(json, sessionEvents))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(Api::class.java)
    }

    @Test
    fun `401 on a request carrying a bearer token signals a forced logout`() = runTest {
        val events = SessionEvents()
        val received = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.forcedLogout.collect { received.add(Unit) }
        }
        val api = buildApi(TokenProvider { "live-token" }, events)
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error": "invalid_token", "message": "revoked"}"""),
        )

        try {
            api.lists()
            fail("expected UnauthorizedException")
        } catch (_: UnauthorizedException) {
            // expected
        }
        advanceUntilIdle()

        assertEquals(1, received.size)
    }

    @Test
    fun `401 with no bearer token (a login failure) does NOT signal a forced logout`() = runTest {
        val events = SessionEvents()
        val received = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.forcedLogout.collect { received.add(Unit) }
        }
        val api = buildApi(TokenProvider { null }, events)
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error": "invalid_credentials", "message": "wrong password"}"""),
        )

        try {
            api.lists()
            fail("expected UnauthorizedException")
        } catch (_: UnauthorizedException) {
            // expected
        }
        advanceUntilIdle()

        assertEquals(0, received.size)
    }

    @Test
    fun `a non-401 error does not signal a forced logout`() = runTest {
        val events = SessionEvents()
        val received = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.forcedLogout.collect { received.add(Unit) }
        }
        val api = buildApi(TokenProvider { "live-token" }, events)
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error": "email_taken", "message": "exists"}"""),
        )

        try {
            api.register(RegisterRequest("a@example.com", "pw"))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("email_taken", e.code)
        }
        advanceUntilIdle()

        assertEquals(0, received.size)
    }
}
