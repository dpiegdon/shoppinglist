package org.p23q.shoppinglist.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

/**
 * Verifies the DEBUG behaviour of [applyDevCertTrust] (this test compiles against the debug source
 * set's implementation): with the flag on it connects to a self-signed server; with it off it does
 * not. The complementary [org.p23q.shoppinglist.data.api release-variant test] proves the release
 * no-op never bypasses.
 */
class DevCertTrustDebugTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // A self-signed cert (its own issuer) — nothing in the default trust store vouches for it.
        val heldCert = HeldCertificate.Builder().addSubjectAlternativeName(server.hostName).build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(heldCert).build()
        server.useHttps(serverCerts.sslSocketFactory(), false)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `flag on trusts a self-signed server`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = OkHttpClient.Builder().applyDevCertTrust(allowSelfSignedCerts = true).build()

        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun `flag off rejects a self-signed server`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = OkHttpClient.Builder().applyDevCertTrust(allowSelfSignedCerts = false).build()

        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            fail("expected an untrusted self-signed cert to be rejected")
        } catch (_: SSLHandshakeException) {
            // expected
        }
    }
}
