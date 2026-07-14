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
 * Robolectric. [dispatcher] is public so a test that needs to await more than one sequential
 * async call deterministically can pass it into `runTest(mainDispatcherRule.dispatcher) { }` —
 * otherwise runTest's own TestCoroutineScheduler and this rule's Main dispatcher are two
 * independent schedulers that can race (a job's .join() can return before Main-dispatched state
 * updates are actually visible). See T-66.
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
