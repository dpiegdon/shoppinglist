package org.p23q.shoppinglist.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.core.api.ProtocolState
import org.p23q.shoppinglist.core.api.SessionEvents
import org.p23q.shoppinglist.data.AuthRepository

@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuthRepository : AuthRepository {
        var clearLocalSessionCalled = false
        override suspend fun register(email: String, password: String) {}
        override suspend fun login(email: String, password: String) {}
        override suspend fun logout() {}
        override suspend fun clearLocalSession() { clearLocalSessionCalled = true }
        override suspend fun registrationAllowed(): Boolean = true
        override fun lastOpenedListId(): String? = null
    }

    @Test
    fun `forcedLogout re-exposes the SessionEvents signal`() = runTest(mainDispatcherRule.dispatcher) {
        val events = SessionEvents()
        val viewModel = RootViewModel(events, FakeAuthRepository())
        val received = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.forcedLogout.collect { received.add(Unit) }
        }

        events.notifyForcedLogout()
        advanceUntilIdle()

        assertEquals(1, received.size)
    }

    @Test
    fun `updateRequired re-exposes the protocol state the root blocks on (T-244)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val protocolState = ProtocolState()
            val viewModel = RootViewModel(SessionEvents(), FakeAuthRepository(), protocolState)

            assertFalse(viewModel.updateRequired.value)
            protocolState.notifyClientOutdated()

            // A state, not an event: the root reads the current value, so a screen recomposing
            // after the refusal still blocks instead of having missed the moment.
            assertTrue(viewModel.updateRequired.value)
        }

    @Test
    fun `onForcedLogout clears the local session`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeAuthRepository()
        val viewModel = RootViewModel(SessionEvents(), repo)

        viewModel.onForcedLogout().join()

        assertTrue(repo.clearLocalSessionCalled)
    }
}
