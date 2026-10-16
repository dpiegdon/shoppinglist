package org.p23q.shoppinglist.core.api

import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun buildApi(tokenProvider: TokenProvider): Api =
        createApi(server.url("/").toString(), OkHttp.create(), json, tokenProvider)

    @Test
    fun `auth header is injected when a token is present`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"lists": []}"""))
        val api = buildApi(TokenProvider { "abc-token" })

        api.lists()

        val recorded = server.takeRequest()
        assertEquals("Bearer abc-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `no auth header is added when there is no token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"lists": []}"""))
        val api = buildApi(TokenProvider { null })

        api.lists()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `subpath in the base URL is preserved in the request path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"lists": []}"""))
        createApi(server.url("/my/stuff/shoppinglist/").toString(), OkHttp.create(), json, TokenProvider { null }).lists()

        val recorded = server.takeRequest()
        assertEquals("/my/stuff/shoppinglist/api/v1/lists", recorded.path)
    }

    @Test
    fun `401 response throws a typed UnauthorizedException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error": "invalid_token", "message": "token expired"}"""),
        )
        val api = buildApi(TokenProvider { "expired-token" })

        try {
            api.lists()
            fail("expected UnauthorizedException")
        } catch (e: UnauthorizedException) {
            assertEquals("token expired", e.message)
        }
    }

    @Test
    fun `non-401 error response parses the error envelope into a typed ApiException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(410)
                .setBody("""{"error": "full_resync_required", "message": "cursor too old"}"""),
        )
        val api = buildApi(TokenProvider { "token" })

        try {
            api.sync(SyncRequest(cursor = 1, deviceId = "dev-uuid"))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("full_resync_required", e.code)
            assertEquals("cursor too old", e.message)
            assertEquals(410, e.httpStatus)
        }
    }
}
