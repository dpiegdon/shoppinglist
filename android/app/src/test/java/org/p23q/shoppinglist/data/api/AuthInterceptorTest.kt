package org.p23q.shoppinglist.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Mirrors the web fix (T-89, ab7e059): credential-exchange endpoints (/login, /register) must
 * never carry the current session token, so a wrong-password 401 there can't look like a revoked
 * session and trip ErrorInterceptor's forced-logout path (see ErrorInterceptorTest). /logout must
 * keep its token — the server needs it to know which session to revoke.
 */
class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(TokenProvider { "live-token" }))
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun authHeaderFor(path: String): String? {
        val request = Request.Builder().url(server.url(path)).build()
        client.newCall(request).execute().close()
        return server.takeRequest().getHeader("Authorization")
    }

    @Test
    fun `omits Authorization on a request to login even with a token present`() {
        assertNull(authHeaderFor("api/v1/login"))
    }

    @Test
    fun `omits Authorization on a request to register even with a token present`() {
        assertNull(authHeaderFor("api/v1/register"))
    }

    @Test
    fun `omits Authorization on login when the server is mounted under a path prefix`() {
        assertNull(authHeaderFor("shopping/api/v1/login"))
    }

    @Test
    fun `keeps Authorization on logout so the server knows which session to revoke`() {
        assertEquals("Bearer live-token", authHeaderFor("api/v1/logout"))
    }

    @Test
    fun `keeps Authorization on an ordinary endpoint`() {
        assertEquals("Bearer live-token", authHeaderFor("api/v1/sync"))
    }
}
