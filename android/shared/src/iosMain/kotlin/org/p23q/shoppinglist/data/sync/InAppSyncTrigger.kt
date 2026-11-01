package org.p23q.shoppinglist.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.p23q.shoppinglist.core.sync.SyncTrigger
import org.p23q.shoppinglist.core.sync.Syncer

/**
 * The syncs an open iOS app runs by itself, in place of Android's WorkManager (`SyncScheduler`):
 * one 5 s after the last edit, one at once on foreground and after login. Only while the app is
 * open; what runs while it is closed is step 4 (T-354).
 *
 * A request replaces the one still waiting, so a burst of edits costs one sync. A sync that has
 * started is never cut short by the next request, which waits for it: two syncs never overlap.
 */
class InAppSyncTrigger(
    /** Outlives every screen; the app's own scope. */
    private val scope: CoroutineScope,
    /** Resolved per sync, not at construction: the engine is built on the repos this trigger serves. */
    private val syncer: () -> Syncer,
    private val afterEditDelayMillis: Long = AFTER_EDIT_DELAY_MILLIS,
) : SyncTrigger {

    private val running = Mutex()
    private var waiting: Job? = null

    override fun scheduleAfterEdit() = schedule(afterEditDelayMillis)

    override fun scheduleImmediate() = schedule(0)

    private fun schedule(delayMillis: Long) {
        waiting?.cancel()
        waiting = scope.launch {
            delay(delayMillis)
            // Past the delay, the sync runs to its end even if a later request cancels this job.
            withContext(NonCancellable) { syncOnce() }
        }
    }

    private suspend fun syncOnce() {
        running.withLock {
            try {
                syncer().syncNow(emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A sync's result is in SyncStatus; a sync that threw waits for the next request.
            }
        }
    }

    internal companion object {
        /** As `SyncScheduler.afterEditRequest`: a burst of edits is one sync. */
        const val AFTER_EDIT_DELAY_MILLIS = 5_000L
    }
}
