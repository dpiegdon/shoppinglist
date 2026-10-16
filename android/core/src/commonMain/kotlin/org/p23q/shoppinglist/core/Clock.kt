package org.p23q.shoppinglist.core

import kotlin.time.ExperimentalTime

/**
 * Wall-clock time in epoch milliseconds: the field clocks this device stamps on its edits, a new
 * row's `createdAt`, and the time of the last successful sync. Injected, so a test can fix it.
 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        /** The platform's system clock. */
        @OptIn(ExperimentalTime::class)
        val System: Clock = Clock { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    }
}
