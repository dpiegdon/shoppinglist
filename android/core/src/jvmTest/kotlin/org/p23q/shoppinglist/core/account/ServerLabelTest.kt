package org.p23q.shoppinglist.core.account

import org.junit.Assert.assertEquals
import org.junit.Test

/** The default account label: the server's host, as java.net.URI reads it, or else the URL itself. */
class ServerLabelTest {

    @Test
    fun `is the host, without user, port or path`() {
        assertEquals("lists.example.com", serverLabel("https://lists.example.com/"))
        assertEquals("lists.example.com", serverLabel("https://me@lists.example.com:8443/shop/"))
        assertEquals("[fd00::1]", serverLabel("https://[fd00::1]:8443/"))
        assertEquals("192.168.1.20", serverLabel("http://192.168.1.20:5000/"))
    }

    @Test
    fun `is the URL when it names no host`() {
        assertEquals("lists.example.com/", serverLabel("lists.example.com/"))
        assertEquals("https://my_host.lan/", serverLabel("https://my_host.lan/"))
        assertEquals("https://lists.example.com:http/", serverLabel("https://lists.example.com:http/"))
    }

    @Test
    fun `agrees with java net URI`() {
        val urls = listOf(
            "https://lists.example.com/", "https://Lists.Example.com/", "http://localhost:5000/", "https://a.b.c./",
            "https://me@host.example/", "https://user:pw@host.example:1/x?y#z", "https://[fd00::1]/", "https://[::1]:80/",
            "https://10.0.0.1/", "https://300.1.1.1/", "https://1.2.3/", "https://123.45/", "https://host-.example/",
            "https://-host.example/", "https://my_host.lan/", "https://host:/", "https://host:12x/", "https://host/a b/",
            "https:///path/", "https://", "host/", "", "ftp://files.example.com/", "https://xn--bcher-kva.example/",
            "https://host.example/{x}/", "1http://h.example/", "https://h.example:8443/a/b/",
        )
        for (url in urls) {
            val expected = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
            assertEquals(url, expected, serverLabel(url))
        }
    }
}
