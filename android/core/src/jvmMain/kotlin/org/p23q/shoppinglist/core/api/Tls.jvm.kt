package org.p23q.shoppinglist.core.api

import javax.net.ssl.SSLException

actual fun Throwable.isTlsFailure(): Boolean = this is SSLException
