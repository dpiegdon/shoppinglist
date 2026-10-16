package org.p23q.shoppinglist.data.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Interceptor
import okhttp3.Response
import org.p23q.shoppinglist.core.api.HttpEngineFactory

/**
 * The engine the app's API clients run on (`app/…/data/api/OkHttpEngineFactory.kt`), for the tests
 * here, which cannot see :app: OkHttp following redirects, and a DELETE that carries nothing sent
 * with no body. Without the app's debug-only certificate opt-in, which no test here uses.
 */
class OkHttpEngineFactory : HttpEngineFactory {
    override fun create(allowSelfSignedCerts: Boolean): HttpClientEngine = OkHttp.create {
        config {
            followRedirects(true)
            followSslRedirects(true)
            addInterceptor(BodilessDelete)
        }
    }

    override val userAgent: String = "okhttp/${okhttp3.OkHttp.VERSION}"
}

private object BodilessDelete : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "DELETE" || request.body?.contentLength() != 0L) return chain.proceed(request)
        return chain.proceed(request.newBuilder().method("DELETE", null).removeHeader("Content-Length").build())
    }
}
