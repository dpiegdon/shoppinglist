package org.p23q.shoppinglist.data

import androidx.annotation.StringRes
import org.p23q.shoppinglist.R

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

    /** Pre-T-110 lists and older servers carry no kind — they stay shopping lists. */
    const val DEFAULT = SHOPPING

    /** Normalizes anything unexpected (an unknown kind from a newer client) to the default. */
    fun of(raw: String?): String = if (raw == CHECKLIST) CHECKLIST else SHOPPING

    /** Whether the shopping-only item fields (stores, quantity, price) are shown for this kind. */
    fun showsShoppingFields(kind: String?): Boolean = of(kind) == SHOPPING

    /** A string RESOURCE, not a String: this is user-facing and must follow the chosen language
     *  (T-111), and this object has no Context to resolve one with. */
    @StringRes
    fun label(kind: String?): Int =
        if (of(kind) == CHECKLIST) R.string.list_kind_checklist else R.string.list_kind_shopping

    /** Overview glyph — a cart for shopping, a check for a plain checklist. */
    fun icon(kind: String?): String = if (of(kind) == CHECKLIST) "✓" else "🛒"
}
