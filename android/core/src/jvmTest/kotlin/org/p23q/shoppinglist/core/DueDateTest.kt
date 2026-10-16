package org.p23q.shoppinglist.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web's dueState cases in format.test.ts, on Android's side (T-323). */
class DueDateTest {

    @Test
    fun `before today is overdue, today is today, after is upcoming`() {
        assertEquals(DueState.OVERDUE, DueDate.state("2026-09-27", "2026-09-28"))
        assertEquals(DueState.OVERDUE, DueDate.state("2025-12-31", "2026-01-01"))
        assertEquals(DueState.TODAY, DueDate.state("2026-09-28", "2026-09-28"))
        assertEquals(DueState.UPCOMING, DueDate.state("2026-09-29", "2026-09-28"))
    }

    @Test
    fun `only a checklist offers a due date`() {
        assertTrue(ListKind.showsDueDate(ListKind.CHECKLIST))
        assertFalse(ListKind.showsDueDate(ListKind.SHOPPING))
        assertFalse(ListKind.showsDueDate(ListKind.EXPENSES))
        assertFalse(ListKind.showsDueDate(null))
    }
}
