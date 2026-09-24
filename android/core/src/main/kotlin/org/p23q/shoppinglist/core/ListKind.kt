package org.p23q.shoppinglist.core

/**
 * What a list is (T-110). A checklist is a shopping list minus the shopping-only item fields
 * (stores/price/quantity); everything else — categories, notes, statuses, registry, sharing,
 * sync — is identical. Purely a display toggle over one shared item schema, so converting a list
 * needs no data migration and is reversible.
 *
 * Mirror of the web client's `lib/listKind.ts`; the rule lives in
 * docs/archive/specs/client-ui-notes.md so the two clients can't drift.
 */
object ListKind {
    const val SHOPPING = "shopping"
    const val CHECKLIST = "checklist"

    /**
     * A list of shared expenses (T-151). Not a display toggle like the two above: its items carry
     * the money tuple instead of the shopping fields, their names are not unique, and the server
     * refuses to convert a list to or from this kind for its whole life.
     */
    const val EXPENSES = "expenses"

    /** Pre-T-110 lists and older servers carry no kind — they stay shopping lists. */
    const val DEFAULT = SHOPPING

    /** Normalizes anything unexpected (an unknown kind from a newer client) to the default. */
    fun of(raw: String?): String = when (raw) {
        CHECKLIST -> CHECKLIST
        EXPENSES -> EXPENSES
        else -> SHOPPING
    }

    /** Whether this list holds shared expenses rather than things to buy (T-151). */
    fun isExpenses(kind: String?): Boolean = of(kind) == EXPENSES

    /** Whether the shopping-only item fields (stores, quantity, price) are shown for this kind. */
    fun showsShoppingFields(kind: String?): Boolean = of(kind) == SHOPPING

    /** Overview glyph — a cart for shopping, a check for a checklist, a banknote for expenses. */
    fun icon(kind: String?): String = when (of(kind)) {
        CHECKLIST -> "✓"
        EXPENSES -> "💶"
        else -> "🛒"
    }
}
