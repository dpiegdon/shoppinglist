package org.p23q.shoppinglist.data.db

import androidx.annotation.StringRes
import org.p23q.shoppinglist.R

/**
 * Item status; [wireValue] is the exact string used on the wire and stored in the DB column, while
 * [label] names the user-facing string RESOURCE shown in the UI (T-40) — never surface the raw
 * wire value.
 *
 * A resource id rather than a String since T-111: this enum has no Context to resolve one with,
 * and the label has to follow the chosen language like everything else.
 *
 * One canonical wording, shared by the Registry chips and the item dialog and matching the web
 * client word for word (T-124).
 */
enum class Status(val wireValue: String, @param:StringRes val label: Int) {
    BACKLOG("backlog", R.string.status_backlog),
    TODO("todo", R.string.status_todo),
    CHECKED("checked", R.string.status_checked);

    companion object {
        fun fromWireValue(value: String): Status = entries.first { it.wireValue == value }
    }
}
