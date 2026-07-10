package org.p23q.shoppinglist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Exercises [openWithRecovery]'s control flow directly — the real SessionStore path needs a Keystore
 * that Robolectric can't provide, which is exactly why the recovery logic was extracted (T-37).
 */
class SessionStoreRecoveryTest {

    @Test
    fun `returns the built value and never cleans up when build succeeds`() {
        var cleaned = false

        val result = openWithRecovery(build = { "ok" }, onCorrupt = { cleaned = true })

        assertEquals("ok", result)
        assertFalse(cleaned)
    }

    @Test
    fun `on an undecryptable keyset it drops the store and rebuilds once`() {
        var attempts = 0
        var cleaned = false

        val result = openWithRecovery(
            build = {
                attempts++
                if (attempts == 1) throw GeneralSecurityException("bad keyset") else "rebuilt"
            },
            onCorrupt = { cleaned = true },
        )

        assertEquals("rebuilt", result)
        assertTrue(cleaned)
        assertEquals(2, attempts)
    }

    @Test
    fun `it also recovers from an unreadable file`() {
        var attempts = 0

        val result = openWithRecovery(
            build = {
                attempts++
                if (attempts == 1) throw IOException("corrupt") else "rebuilt"
            },
            onCorrupt = {},
        )

        assertEquals("rebuilt", result)
    }

    @Test
    fun `a second failure propagates - genuinely unrecoverable`() {
        var cleaned = false

        assertThrows(GeneralSecurityException::class.java) {
            openWithRecovery<String>(
                build = { throw GeneralSecurityException("still bad") },
                onCorrupt = { cleaned = true },
            )
        }

        assertTrue(cleaned)
    }
}
