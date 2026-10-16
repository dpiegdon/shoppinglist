package org.p23q.shoppinglist.core.api

import javax.net.ssl.SSLException

/**
 * Whether this transport failure is a TLS failure, typically a server certificate this device does
 * not trust (T-38): the one network error worth its own message.
 */
fun Throwable.isTlsFailure(): Boolean = this is SSLException
