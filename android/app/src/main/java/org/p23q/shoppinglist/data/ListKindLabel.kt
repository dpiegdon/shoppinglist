package org.p23q.shoppinglist.data

import androidx.annotation.StringRes
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.ListKind

// ListKind lives in :core, which cannot see Android resources; its user-facing label stays here.

/** A string RESOURCE, not a String: this is user-facing and must follow the chosen language
 *  (T-111), and this object has no Context to resolve one with. */
@StringRes
fun ListKind.label(kind: String?): Int = when (of(kind)) {
    CHECKLIST -> R.string.list_kind_checklist
    EXPENSES -> R.string.list_kind_expenses
    else -> R.string.list_kind_shopping
}
