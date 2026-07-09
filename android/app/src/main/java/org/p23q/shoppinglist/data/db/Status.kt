package org.p23q.shoppinglist.data.db

/** Item status; [wireValue] is the exact string used on the wire and stored in the DB column. */
enum class Status(val wireValue: String) {
    BACKLOG("backlog"),
    TODO("todo"),
    CHECKED("checked");

    companion object {
        fun fromWireValue(value: String): Status = entries.first { it.wireValue == value }
    }
}
