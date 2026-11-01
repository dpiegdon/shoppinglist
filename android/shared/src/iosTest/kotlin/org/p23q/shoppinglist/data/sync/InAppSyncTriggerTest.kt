package org.p23q.shoppinglist.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.Syncer
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class InAppSyncTriggerTest {

    /** Counts syncs; each one waits on [gate] when it is set, so a test can hold a sync open. */
    private class CountingSyncer : Syncer {
        var syncs = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun syncNow(fullLists: List<String>): SyncResult {
            syncs++
            gate?.await()
            return SyncResult.Success(0, 0, 0, 0)
        }
    }

    @Test
    fun `a burst of edits is one sync after the delay`() = runTest {
        val syncer = CountingSyncer()
        val trigger = InAppSyncTrigger(scope = this, syncer = { syncer }, afterEditDelayMillis = 5_000)

        repeat(3) { trigger.scheduleAfterEdit(); advanceTimeBy(1_000) }
        assertEquals(0, syncer.syncs)

        advanceTimeBy(5_001)
        assertEquals(1, syncer.syncs)
    }

    @Test
    fun `an immediate request does not wait`() = runTest {
        val syncer = CountingSyncer()
        val trigger = InAppSyncTrigger(scope = this, syncer = { syncer }, afterEditDelayMillis = 5_000)

        trigger.scheduleAfterEdit()
        trigger.scheduleImmediate()
        runCurrent()
        assertEquals(1, syncer.syncs)

        // The edit's own sync was replaced by the immediate one, not queued behind it.
        advanceTimeBy(10_000)
        assertEquals(1, syncer.syncs)
    }

    @Test
    fun `a sync in progress runs to its end and the next waits for it`() = runTest {
        val syncer = CountingSyncer()
        val trigger = InAppSyncTrigger(scope = this, syncer = { syncer }, afterEditDelayMillis = 5_000)
        syncer.gate = CompletableDeferred()

        trigger.scheduleImmediate()
        runCurrent()
        assertEquals(1, syncer.syncs)

        trigger.scheduleImmediate()
        runCurrent()
        // Held up by the first: not started, and the first was not cancelled.
        assertEquals(1, syncer.syncs)

        syncer.gate!!.complete(Unit)
        syncer.gate = null
        runCurrent()
        assertEquals(2, syncer.syncs)
    }

    @Test
    fun `a sync that throws does not stop the next`() = runTest {
        var calls = 0
        val syncer = Syncer { calls++; if (calls == 1) error("server down") else SyncResult.Success(0, 0, 0, 0) }
        val trigger = InAppSyncTrigger(scope = this, syncer = { syncer }, afterEditDelayMillis = 0)

        trigger.scheduleImmediate()
        runCurrent()
        trigger.scheduleImmediate()
        runCurrent()
        assertEquals(2, calls)
    }
}
