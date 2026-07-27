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
 * Note these are the Registry screen's short chip labels ("To buy", "Done") and are deliberately
 * NOT the same strings as the item dialog's radio options ("Todo", "Checked", "Backlog (not on
 * list)"). Unifying that wording is a UX decision, not a mechanical one.
 */
enum class Status(val wireValue: String, @param:StringRes val label: Int) {
    BACKLOG("backlog", R.string.status_chip_backlog),
    TODO("todo", R.string.status_chip_todo),
    CHECKED("checked", R.string.status_chip_checked);

    companion object {
        fun fromWireValue(value: String): Status = entries.first { it.wireValue == value }
    }
}
