package org.p23q.shoppinglist.core.api

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSURLErrorDomain

/** NSURLErrorClientCertificateRequired (-1206) up to NSURLErrorSecureConnectionFailed (-1200). */
private val SECURE_CONNECTION_ERRORS = -1206L..-1200L

actual fun Throwable.isTlsFailure(): Boolean =
    this is DarwinHttpRequestException && origin.domain == NSURLErrorDomain && origin.code in SECURE_CONNECTION_ERRORS
