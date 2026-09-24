package org.p23q.shoppinglist.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

/** The web's format.test.ts, on Android's side (T-180, T-187). */
class AppFormatTest {

    // Formatting data varies in its spaces between ICU/CLDR versions (a no-break space in one, a
    // narrow one in the next), so these compare with every kind of space folded to a plain one.
    private fun plain(s: String) = s.replace(Regex("[\\s  ]"), " ")

    private val de = Locale.GERMAN
    private val en = Locale.US

    @Test
    fun `a currency code is formatted the way the language does`() {
        assertEquals("64,00 €", plain(AppFormat.money(6400, "EUR", de)))
        assertEquals("€64.00", AppFormat.money(6400, "EUR", en))
        assertEquals("-€32.00", AppFormat.money(-3200, "EUR", en))
    }

    @Test
    fun `a free-text unit stays a label after the number`() {
        assertEquals("12.00 pizza slices", AppFormat.money(1200, "pizza slices", en))
        assertEquals("12,50 Tokens", plain(AppFormat.money(1250, "Tokens", de)))
    }

    @Test
    fun `cents a currency does not use are dropped, unless the amount has some`() {
        assertEquals("¥1,500", AppFormat.money(150000, "JPY", en))
        assertEquals("¥1,500.50", AppFormat.money(150050, "JPY", en))
    }

    @Test
    fun `a bare number has two decimals`() {
        assertEquals("64,00", AppFormat.number(6400, de))
        assertEquals("64.00", AppFormat.number(6400, en))
    }

    @Test
    fun `a credit is signed, a debt keeps its minus and a square balance stays bare`() {
        assertEquals("+€32.00", AppFormat.signedMoney(3200, "EUR", en))
        assertEquals("-€32.00", AppFormat.signedMoney(-3200, "EUR", en))
        assertEquals("€0.00", AppFormat.signedMoney(0, "EUR", en))
        assertEquals("+32,00 €", plain(AppFormat.signedMoney(3200, "EUR", de)))
        assertEquals("-32,00 €", plain(AppFormat.signedMoney(-3200, "EUR", de)))
    }

    @Test
    fun `a signed amount keeps the language's own formatting`() {
        assertEquals("+￥1,500", AppFormat.signedMoney(150000, "JPY", Locale.JAPANESE))
        assertEquals("+¥1,500.50", AppFormat.signedMoney(150050, "JPY", en))
        assertEquals("+12.00 pizza slices", AppFormat.signedMoney(1200, "pizza slices", en))
        assertEquals("+12,50 Tokens", plain(AppFormat.signedMoney(1250, "Tokens", de)))
    }

    @Test
    fun `Arabic's plus sits where Arabic's minus sits, bidi marks and all`() {
        // Not a "+" glued to the front: in an RTL line the sign belongs where the language writes
        // it, after the marks that keep the digits and the currency in order.
        val ar = Locale.forLanguageTag("ar")
        val credit = AppFormat.signedMoney(6400, "EUR", ar)
        val debt = AppFormat.money(-6400, "EUR", ar)
        assertEquals(debt.replace('-', '+'), credit)
        assertEquals(debt.indexOf('-'), credit.indexOf('+'))
        assertTrue("the sign should not be first in an RTL amount", credit.indexOf('+') > 0)
    }

    @Test
    fun `a settled figure is signed the way the language signs a bare number`() {
        // The counterpart of signedMoney for the balances screen's "settled" (T-245): no currency,
        // because it sits inside a line that already names one.
        assertEquals("+20.00", AppFormat.signedNumber(2000, en))
        assertEquals("-20.00", AppFormat.signedNumber(-2000, en))
        assertEquals("0.00", AppFormat.signedNumber(0, en))
        assertEquals("+20,00", AppFormat.signedNumber(2000, de))
        assertEquals("+1,500.00", AppFormat.signedNumber(150000, Locale.JAPANESE))
    }

    @Test
    fun `a signed number puts Arabic's plus where Arabic's minus goes`() {
        val ar = Locale.forLanguageTag("ar")
        val credit = AppFormat.signedNumber(6400, ar)
        val debt = AppFormat.number(-6400, ar)
        assertEquals(debt.replace('-', '+'), credit)
        assertEquals(debt.indexOf('-'), credit.indexOf('+'))
    }

    @Test
    fun `a calendar date is written as the language does`() {
        assertEquals("Sep 17, 2026", AppFormat.calendarDate("2026-09-17", en))
        assertEquals("17.09.2026", AppFormat.calendarDate("2026-09-17", de))
        // Never worse than what was stored.
        assertEquals("not a date", AppFormat.calendarDate("not a date", en))
    }

    @Test
    fun `a moment is written in the same style`() {
        val noonUtc = java.time.LocalDate.of(2026, 9, 17).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Sep 17, 2026", AppFormat.day(noonUtc, en, ZoneOffset.UTC))
    }
}
