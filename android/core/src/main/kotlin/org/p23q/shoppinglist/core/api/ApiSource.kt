package org.p23q.shoppinglist.core.api

/**
 * Hands out the [Api] client for the configured server. Implemented in :app by ApiProvider, which
 * assembles it from Retrofit and OkHttp and rebuilds it when the server URL changes.
 */
fun interface ApiSource {
    suspend fun get(): Api
}
