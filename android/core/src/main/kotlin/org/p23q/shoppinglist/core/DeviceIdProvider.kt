package org.p23q.shoppinglist.core

/** Source of the local device's id, stamped into every field clock this device writes. */
fun interface DeviceIdProvider {
    suspend fun get(): String
}
