package org.p23q.shoppinglist.core.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * T-240: every request carries the protocol header, credential exchanges included — the server
 * compares it BEFORE it authenticates, so a request without it is refused whoever sends it.
 */
class ProtocolInterceptorTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .addInterceptor(ProtocolInterceptor())
        .addInterceptor(AuthInterceptor(TokenProvider { "live-token" }))
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun protocolHeaderFor(path: String): String? {
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
        return server.takeRequest().getHeader(PROTOCOL_HEADER)
    }

    @Test
    fun `every endpoint gets the header, login and register included`() {
        for (path in listOf(
            "api/v1/login",
            "api/v1/register",
            "api/v1/sync",
            "api/v1/lists",
            "api/v1/app-version",
            "shopping/api/v1/sync",
        )) {
            assertEquals("no protocol header on /$path", PROTOCOL_VERSION.toString(), protocolHeaderFor(path))
        }
    }

    @Test
    fun `the header is the version this build actually holds`() {
        // Not a literal: the whole point is that the number on the wire is the constant the rest
        // of the client (and the web's protocolVersion test) compares against.
        assertEquals("X-Client-Protocol", PROTOCOL_HEADER)
        assertEquals(PROTOCOL_VERSION.toString(), protocolHeaderFor("api/v1/sync"))
    }
}
