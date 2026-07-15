package org.p23q.shoppinglist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Points viewModelScope's Dispatchers.Main at a test dispatcher so ViewModel tests don't need
 * Robolectric. [dispatcher] is public: **every test using this rule must pass it into
 * `runTest(mainDispatcherRule.dispatcher) { }`**, not bare `runTest { }` — otherwise runTest's own
 * TestCoroutineScheduler and this rule's Main dispatcher are two independent schedulers that can
 * race (a job's .join() can return before Main-dispatched state updates are actually visible).
 *
 * This alone is NOT sufficient to eliminate all flakiness (T-66): under full-suite load, a
 * separate, more severe race can still occur — `IllegalStateException: Dispatchers.Main is used
 * concurrently with setting it`, thrown from kotlinx-coroutines-test's own concurrent-modification
 * guard. Dispatchers.Main is single global JVM state; Robolectric's SandboxTestRunner executes
 * test bodies on its own ThreadPoolExecutor-backed sandbox threads, and UnconfinedTestDispatcher's
 * eager cross-thread resumption means a coroutine from one test can still be touching
 * Dispatchers.Main on a background thread just as the *next* test's `starting()` reinstalls it.
 * Confirmed via full-suite runs failing where the identical tests pass every time in isolation —
 * see T-66's notes for the diagnostic detail. Fixing this fully likely requires either switching
 * to StandardTestDispatcher (a large migration: many tests rely on Unconfined's eager-execution
 * semantics and would need explicit `advanceUntilIdle()` calls added) or explicit ViewModel
 * coroutine-scope cancellation between tests. Not yet done.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
