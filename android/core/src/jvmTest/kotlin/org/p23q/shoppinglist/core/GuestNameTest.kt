package org.p23q.shoppinglist.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The guest-name rule (T-364): the server-message rule's trimming and refusals, at most 64. */
class GuestNameTest {

    @Test
    fun `a name is trimmed and kept`() {
        assertEquals("Dave", GuestName.normalize("  Dave\t"))
        assertEquals("Eve, Jr.", GuestName.normalize("Eve, Jr."))
    }

    @Test
    fun `blank, too long or with a control character is refused`() {
        assertNull(GuestName.normalize("   "))
        assertNull(GuestName.normalize("Da\nve"))
        assertNull(GuestName.normalize("x".repeat(65)))
        assertEquals("x".repeat(64), GuestName.normalize("x".repeat(64)))
        // Counted in characters, not UTF-16 units.
        assertEquals("😀".repeat(64), GuestName.normalize("😀".repeat(64)))
    }
}
