package org.p23q.shoppinglist.core.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/** A new OkHttp engine for each client, the engine :app uses; counts the engines it built. */
class TestEngines : HttpEngineFactory {
    var built = 0
        private set

    override fun create(allowSelfSignedCerts: Boolean): HttpClientEngine {
        built++
        return OkHttp.create()
    }
}
