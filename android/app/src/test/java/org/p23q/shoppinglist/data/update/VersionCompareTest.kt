package org.p23q.shoppinglist.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-135: version ordering, which is where an update check most easily goes quietly wrong. */
class VersionCompareTest {

    @Test
    fun `a two-digit minor sorts above a one-digit one`() {
        // The headline case, and the reason this function exists instead of a String compare:
        // lexicographically "1.9.3" > "1.10.0", so a naive check would have told every 1.9.3
        // user that 1.10.0 was older than what they already had. This project shipped exactly
        // that transition.
        assertTrue(compareVersions("1.10.0", "1.9.3")!! > 0)
        assertTrue(compareVersions("1.9.3", "1.10.0")!! < 0)
    }

    @Test
    fun `equal versions compare equal`() {
        assertEquals(0, compareVersions("1.12.0", "1.12.0"))
    }

    @Test
    fun `missing trailing fields count as zero`() {
        assertEquals(0, compareVersions("1.12", "1.12.0"))
        assertEquals(0, compareVersions("2", "2.0.0"))
        assertTrue(compareVersions("1.12.1", "1.12")!! > 0)
    }

    @Test
    fun `ordering works across each field`() {
        assertTrue(compareVersions("2.0.0", "1.99.99")!! > 0)
        assertTrue(compareVersions("1.2.10", "1.2.9")!! > 0)
        assertTrue(compareVersions("1.0.0", "1.0.1")!! < 0)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(0, compareVersions(" 1.12.0 ", "1.12.0"))
    }

    @Test
    fun `anything not purely numeric is unparseable rather than a guess`() {
        // Null, not an exception and not a fabricated ordering: these arrive from a server, and
        // "I can't tell" has to reach the caller so it can decline to prompt.
        assertNull(compareVersions("1.2.0-rc1", "1.2.0"))
        assertNull(compareVersions("1.2.0", "v1.2.0"))
        assertNull(compareVersions("", "1.0.0"))
        assertNull(compareVersions("1..0", "1.0.0"))
        assertNull(compareVersions("1.0.", "1.0.0"))
        assertNull(compareVersions("nonsense", "1.0.0"))
        // Wide enough to overflow Int — unparseable beats silently wrapping negative.
        assertNull(compareVersions("99999999999", "1.0.0"))
    }
}
