package org.p23q.shoppinglist.data.db

/**
 * Item status; [wireValue] is the exact string used on the wire and stored in the DB column, while
 * [label] is the user-facing name shown in the UI (T-40) — never surface the raw wire value.
 */
enum class Status(val wireValue: String, val label: String) {
    BACKLOG("backlog", "Backlog"),
    TODO("todo", "To buy"),
    CHECKED("checked", "Done");

    companion object {
        fun fromWireValue(value: String): Status = entries.first { it.wireValue == value }
    }
}
