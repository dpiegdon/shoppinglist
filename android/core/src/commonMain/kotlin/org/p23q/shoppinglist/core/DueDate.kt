package org.p23q.shoppinglist.core

/**
 * Where an item's due date stands against today (T-323), as the web's `dueState` in
 * lib/format.ts. Both are calendar dates `YYYY-MM-DD`, which order as strings. Passive: this only
 * picks the date's colour on the row, nothing fires and nothing is sorted by it.
 */
enum class DueState { OVERDUE, TODAY, UPCOMING }

object DueDate {
    fun state(isoDate: String, today: String): DueState = when {
        isoDate < today -> DueState.OVERDUE
        isoDate == today -> DueState.TODAY
        else -> DueState.UPCOMING
    }
}
