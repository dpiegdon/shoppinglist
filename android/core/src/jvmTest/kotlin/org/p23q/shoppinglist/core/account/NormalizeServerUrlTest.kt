package org.p23q.shoppinglist.core.account

import org.junit.Assert.assertEquals
import org.junit.Test

/** One spelling per server (T-298), so "the same account" does not depend on how it was typed. */
class NormalizeServerUrlTest {

    @Test
    fun `adds the trailing slash and keeps one that is there`() {
        assertEquals("https://lists.example.com/", normalizeServerUrl("https://lists.example.com"))
        assertEquals("https://lists.example.com/", normalizeServerUrl("https://lists.example.com/"))
    }

    @Test
    fun `lower-cases the scheme and the host but not the path`() {
        assertEquals("https://lists.example.com/Shopping/", normalizeServerUrl("HTTPS://Lists.Example.COM/Shopping"))
    }

    @Test
    fun `drops a default port and keeps any other`() {
        assertEquals("https://lists.example.com/shop/", normalizeServerUrl("https://lists.example.com:443/shop"))
        assertEquals("http://lists.example.com/", normalizeServerUrl("http://lists.example.com:80"))
        assertEquals("https://lists.example.com:8443/", normalizeServerUrl("https://lists.example.com:8443"))
        assertEquals("http://lists.example.com:443/", normalizeServerUrl("http://lists.example.com:443"))
    }

    @Test
    fun `an IPv6 literal keeps its colons`() {
        assertEquals("https://[fd00::1]/", normalizeServerUrl("https://[FD00::1]:443"))
        assertEquals("https://[fd00::1]:8443/", normalizeServerUrl("https://[fd00::1]:8443/"))
    }

    @Test
    fun `is idempotent`() {
        val once = normalizeServerUrl("HTTPS://Lists.Example.com:443/Shop")
        assertEquals(once, normalizeServerUrl(once))
    }
}
