package org.p23q.shoppinglist.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

/**
 * The security guarantee of T-46, verified structurally: this test compiles against the RELEASE
 * source set's [applyDevCertTrust], which is a no-op. Even with allowSelfSignedCerts = true, a
 * release build must STILL reject a self-signed server — the trust-all code is not in this variant
 * at all. If this test ever connects, the release build is bypassing TLS and the split is broken.
 */
class DevCertTrustReleaseTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val heldCert = HeldCertificate.Builder().addSubjectAlternativeName(server.hostName).build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(heldCert).build()
        server.useHttps(serverCerts.sslSocketFactory(), false)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `release build never trusts a self-signed server even with the flag on`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = OkHttpClient.Builder().applyDevCertTrust(allowSelfSignedCerts = true).build()

        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
            fail("release variant must not trust a self-signed cert — the debug bypass leaked in")
        } catch (_: SSLHandshakeException) {
            // expected: release applyDevCertTrust is a no-op, so normal validation rejects the cert
        }
    }
}
