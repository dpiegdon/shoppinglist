package org.p23q.shoppinglist.core.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

/**
 * The engine an iOS app runs the API clients on: Darwin's NSURLSession, a new one for each client.
 * The certificate opt-in is the Android debug build's alone, so it is ignored here: NSURLSession
 * checks every certificate against the system's trust store.
 */
class DarwinEngineFactory : HttpEngineFactory {
    override fun create(allowSelfSignedCerts: Boolean): HttpClientEngine = Darwin.create()
}
