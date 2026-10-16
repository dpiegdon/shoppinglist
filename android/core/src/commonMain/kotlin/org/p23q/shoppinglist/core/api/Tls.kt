package org.p23q.shoppinglist.core.api

/**
 * Whether this transport failure is a TLS failure, typically a server certificate this device does
 * not trust (T-38): the one network error worth its own message. On the JVM an SSLException, as
 * OkHttp throws it; on iOS an NSURLErrorDomain error of the secure-connection family.
 */
expect fun Throwable.isTlsFailure(): Boolean
