package org.p23q.shoppinglist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Points viewModelScope's Dispatchers.Main at a test dispatcher so ViewModel tests don't need
 * Robolectric. [dispatcher] is public and **every test using this rule must pass it into
 * `runTest(mainDispatcherRule.dispatcher) { }`**, not bare `runTest { }`, so the test body and the
 * rule's Main dispatcher share one TestCoroutineScheduler rather than two racing ones.
 *
 * Uses [StandardTestDispatcher], NOT UnconfinedTestDispatcher (T-66). Unconfined resumes
 * coroutines *eagerly on whatever thread completes them* — combined with real background threads
 * (Room's IO query context, OkHttp's dispatcher) and Robolectric's own sandbox worker threads,
 * that let a coroutine from one test still be touching the single global Dispatchers.Main on a
 * background thread just as the next test's `starting()` reinstalled it, tripping
 * kotlinx-coroutines-test's concurrent-modification guard ("Dispatchers.Main is used concurrently
 * with setting it") — a real, full-suite-only flake, green in isolation every time. Standard
 * confines all Main-dispatched execution to the single test thread, driven explicitly by the
 * scheduler, so nothing runs on a background thread. The trade-off it forces: coroutines launched
 * on Main (viewModelScope, ViewModel init blocks) no longer run eagerly at launch — a test that
 * fires a `viewModelScope.launch`-backed action and then reads `uiState.value` synchronously must
 * first pump the scheduler (`advanceUntilIdle()`), or await a suspending signal (`job.join()`,
 * `uiState.first { … }`) that pumps it implicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
