package org.p23q.shoppinglist.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** The web's shortDate cases in format.test.ts, with the same dates (T-323). */
@RunWith(RobolectricTestRunner::class)
class ShortDateTest {

    // Spaces vary between ICU versions (a no-break one in one, a narrow one in the next).
    private fun plain(s: String) = s.replace(Regex("[\\s  ]"), " ")

    @Test
    fun `drops the year when it is the current one`() {
        assertEquals("Sep 17", plain(shortDate("2026-09-17", Locale.ENGLISH, "2026-09-28")))
        assertEquals("5. Jan.", plain(shortDate("2026-01-05", Locale.GERMAN, "2026-09-28")))
    }

    @Test
    fun `keeps the year when it is another one`() {
        assertEquals("Jan 5, 2027", plain(shortDate("2027-01-05", Locale.ENGLISH, "2026-09-28")))
        assertEquals("31. Dez. 2025", plain(shortDate("2025-12-31", Locale.GERMAN, "2026-09-28")))
    }

    @Test
    fun `never moves the date a day early`() {
        assertEquals("Mar 1", plain(shortDate("2026-03-01", Locale.ENGLISH, "2026-09-28")))
    }

    @Test
    fun `is never worse than what was stored`() {
        assertEquals("not a date", shortDate("not a date", Locale.ENGLISH, "2026-09-28"))
    }
}
