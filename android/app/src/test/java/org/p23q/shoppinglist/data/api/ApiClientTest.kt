package org.p23q.shoppinglist.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

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
        server.shutdown()
    }

    private fun buildApi(tokenProvider: TokenProvider): Api {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(ErrorInterceptor(json))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(Api::class.java)
    }

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
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(TokenProvider { null }))
            .addInterceptor(ErrorInterceptor(json))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/my/stuff/shoppinglist/").toString())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        retrofit.create(Api::class.java).lists()

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
