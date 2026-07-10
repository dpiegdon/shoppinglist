package org.p23q.shoppinglist.data.api

import okhttp3.OkHttpClient

/**
 * Release counterpart of the debug-only cert-trust bypass — intentionally a NO-OP.
 *
 * The trust-all TLS code exists solely in the debug source set. This release version has the same
 * signature so main code ([ApiProvider]) compiles against both variants, but it ignores the flag
 * and never weakens certificate validation. Consequently a `allowSelfSignedCerts = true` value that
 * somehow reaches a release install (e.g. carried over by backup/restore from a debug build) has no
 * effect: release builds always do normal TLS validation.
 */
@Suppress("UNUSED_PARAMETER")
fun OkHttpClient.Builder.applyDevCertTrust(allowSelfSignedCerts: Boolean): OkHttpClient.Builder = this
