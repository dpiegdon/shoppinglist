package org.p23q.shoppinglist.core.db

/**
 * Item status; [wireValue] is the exact string used on the wire and stored in the DB column. What
 * the UI shows is a string RESOURCE (T-40), `Status.label` in :app, since this module cannot see
 * Android resources — never surface the raw wire value.
 */
enum class Status(val wireValue: String) {
    BACKLOG("backlog"),
    TODO("todo"),
    CHECKED("checked");

    companion object {
        fun fromWireValue(value: String): Status = entries.first { it.wireValue == value }
    }
}
