package org.p23q.shoppinglist.core.api

/**
 * Hands out the [Api] client the single-account screens talk to: the current account's
 * ([org.p23q.shoppinglist.core.account.CurrentAccountApi]).
 */
fun interface ApiSource {
    suspend fun get(): Api
}
