package org.p23q.shoppinglist.core

/**
 * The rule for a guest's name (T-364), checked before anything goes to the server: the
 * server-message rule's trimming and refusals ([ServerMessageRule], pinned by
 * `shared-test-cases/server-message.json`), then 1 to [MAX] characters.
 */
object GuestName {

    const val MAX = 64

    /** The name the server would store, or null where it would answer `invalid_guest_name`. */
    fun normalize(input: String): String? {
        val valid = ServerMessageRule.check(input) as? ServerMessageRule.Result.Valid ?: return null
        val name = valid.message ?: return null
        return name.takeIf { codePointsOf(it).size <= MAX }
    }
}
