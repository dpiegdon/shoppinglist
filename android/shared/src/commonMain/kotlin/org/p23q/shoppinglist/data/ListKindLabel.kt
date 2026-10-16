package org.p23q.shoppinglist.data

import org.jetbrains.compose.resources.StringResource
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.list_kind_checklist
import org.p23q.shoppinglist.resources.list_kind_expenses
import org.p23q.shoppinglist.resources.list_kind_shopping

// ListKind lives in :core, which cannot see the string resources; its user-facing label stays here.

/** A string RESOURCE, not a String: this is user-facing and must follow the chosen language
 *  (T-111), and this object does not know which language that is. */
fun ListKind.label(kind: String?): StringResource = when (of(kind)) {
    CHECKLIST -> Res.string.list_kind_checklist
    EXPENSES -> Res.string.list_kind_expenses
    else -> Res.string.list_kind_shopping
}
