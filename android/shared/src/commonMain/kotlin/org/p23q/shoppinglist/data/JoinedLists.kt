package org.p23q.shoppinglist.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The lists this process has just joined through an invite (T-364), by local id, until the list
 * screen has looked once: right after a join a ledger with unclaimed guests asks "Are you one of
 * these?", and only then. [InviteJoiner][org.p23q.shoppinglist.ui.redeem.InviteJoiner] marks the
 * list; [consume] answers true once and forgets it, so dismissing the chooser is final for that
 * join and reopening the list later asks nothing.
 *
 * The web passes the same flag as a query parameter. Here it is a process-scoped holder instead
 * of a route argument: a list route with an argument the back stack's other entries lack would no
 * longer match them, and "back to this list" from its properties would push a second copy.
 */
class JoinedLists {
    private val joined = MutableStateFlow(emptySet<String>())

    fun mark(listId: String) = joined.update { it + listId }

    fun consume(listId: String): Boolean {
        while (true) {
            val current = joined.value
            if (listId !in current) return false
            if (joined.compareAndSet(current, current - listId)) return true
        }
    }
}
