package org.p23q.shoppinglist.data.api

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * DEBUG-ONLY. When [allowSelfSignedCerts] is set (a developer opt-in in Settings), configures the
 * client to accept ANY server certificate — for testing against a server with a self-signed cert,
 * e.g. the bundled dev server. This defeats TLS server authentication entirely and MUST NOT ship in
 * release: it lives in the debug source set, whose release counterpart (src/release) is a no-op with
 * the same signature, so this code is not compiled into the release APK at all. [OkHttpClient] still
 * negotiates TLS (traffic is encrypted); it just stops verifying WHO the server is.
 */
fun OkHttpClient.Builder.applyDevCertTrust(allowSelfSignedCerts: Boolean): OkHttpClient.Builder {
    if (!allowSelfSignedCerts) return this

    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), SecureRandom())
    }
    sslSocketFactory(sslContext.socketFactory, trustAll)
    hostnameVerifier { _, _ -> true }
    return this
}
