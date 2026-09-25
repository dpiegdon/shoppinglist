package org.p23q.shoppinglist.data

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AppDb
import org.robolectric.Shadows.shadowOf

/**
 * Closes [db] only once nothing reads or writes it any more (T-303).
 *
 * Room's close() closes the connection even while a query on another thread is still stepping it,
 * and on the bundled native SQLite that is a segfault of the whole test JVM rather than a failed
 * test. A query a view model's collector started runs on Room's IO context and carries on after
 * the view model's scope is cancelled, so cancelling is not enough. This cancels every view
 * model's scope and every one of [jobs], waits until each has completed, then waits for
 * [registry]'s background writes ([AccountRegistry.flush]), and only then closes the database.
 *
 * A cancelled coroutine on Dispatchers.Main completes only when Main runs it, so the wait calls
 * [pumpMain] while it waits: [idleMainLooper] under Robolectric's main looper, [runCurrentOn] with
 * a [org.p23q.shoppinglist.MainDispatcherRule]'s dispatcher. If something is still running after
 * [timeoutMs], the database is left open (a leak, not a crash) and this throws.
 */
fun closeWhenIdle(
    db: AppDb,
    pumpMain: () -> Unit,
    viewModels: Iterable<ViewModel> = emptyList(),
    jobs: Iterable<Job> = emptyList(),
    registry: AccountRegistry? = null,
    timeoutMs: Long = 10_000,
) {
    val running = viewModels.map { it.viewModelScope.coroutineContext.job } + jobs
    running.forEach { it.cancel() }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (running.any { !it.isCompleted }) {
        check(System.currentTimeMillis() < deadline) {
            "A cancelled scope was still running after $timeoutMs ms; the test database is left open"
        }
        pumpMain()
        Thread.sleep(1)
    }
    if (registry != null) {
        // Not runBlocking on this thread: a pending registry write is a Room query, and a test
        // database built with setQueryCoroutineContext(testDispatcher) resumes it only when that
        // dispatcher is pumped — which nothing would do while this thread blocks (T-306).
        val flush = CoroutineScope(Dispatchers.Default).launch { registry.flush() }
        while (!flush.isCompleted) {
            check(System.currentTimeMillis() < deadline) {
                "A registry write was still pending after $timeoutMs ms; the test database is left open"
            }
            pumpMain()
            Thread.sleep(1)
        }
    }
    db.close()
}

/** Runs what Robolectric's main looper has due now, as a [closeWhenIdle] pump. */
fun idleMainLooper() {
    shadowOf(Looper.getMainLooper()).idle()
}

/** A [closeWhenIdle] pump that runs what [dispatcher] has due now, without advancing its clock. */
@OptIn(ExperimentalCoroutinesApi::class)
fun runCurrentOn(dispatcher: TestDispatcher): () -> Unit = { dispatcher.scheduler.runCurrent() }
