package org.p23q.shoppinglist.data.api

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import org.p23q.shoppinglist.core.api.AppJson
import org.p23q.shoppinglist.core.api.HttpEngineFactory
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object JsonModule {
    @Provides
    @Singleton
    fun provideJson(): Json = AppJson
}

/**
 * The engine :core's API clients run on: OkHttp, a new one for each client, as each client had
 * its own OkHttpClient before Ktor.
 */
@Singleton
class OkHttpEngineFactory @Inject constructor() : HttpEngineFactory {
    override fun create(allowSelfSignedCerts: Boolean): HttpClientEngine = OkHttp.create {
        config {
            // OkHttp follows redirects itself, as it always has; Ktor's engine turns that off by
            // default, and the client leaves redirects to the engine.
            followRedirects(true)
            followSslRedirects(true)
            addInterceptor(BodilessDelete)
            // No-op in release (see DevCertTrust.kt); honored only in debug builds when the dev
            // opt-in is set, to allow connecting to a self-signed dev server.
            applyDevCertTrust(allowSelfSignedCerts)
        }
    }

    /** OkHttp's own, which it sends when a request names none. */
    override val userAgent: String = "okhttp/${okhttp3.OkHttp.VERSION}"
}

/**
 * Sends a DELETE that carries nothing with no body at all, as it always went out: Ktor's engine
 * hands OkHttp an empty one, which would add `Content-Length: 0`.
 */
private object BodilessDelete : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "DELETE" || request.body?.contentLength() != 0L) return chain.proceed(request)
        return chain.proceed(request.newBuilder().method("DELETE", null).removeHeader("Content-Length").build())
    }
}
