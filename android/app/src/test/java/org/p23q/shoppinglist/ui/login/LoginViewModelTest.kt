package org.p23q.shoppinglist.ui.login

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.data.AuthRepository
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.api.UnauthorizedException
import java.io.File
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.UiText

class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuthRepository(
        private val onLogin: suspend (String, String) -> Unit = { _, _ -> },
        private val lastOpened: String? = null,
    ) : AuthRepository {
        var registerCalled = false
        var loginCalled = false
        var loggedOut = false

        override suspend fun register(email: String, password: String) {
            registerCalled = true
        }

        override suspend fun login(email: String, password: String) {
            loginCalled = true
            onLogin(email, password)
        }

        override suspend fun logout() {
            loggedOut = true
        }

        override suspend fun clearLocalSession() {}

        override fun lastOpenedListId(): String? = lastOpened
    }

    private fun newServerConfig(): ServerConfig {
        val tempFile = File.createTempFile("login_vm_test", ".preferences_pb")
        tempFile.deleteOnExit()
        // Unconfined DataStore scope so the VM's init reads run inline on the test thread and finish
        // within runTest — the default IO scope hops to a real thread whose continuation resumes on
        // Main after the rule reset it, crashing teardown.
        return ServerConfig(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined + Job())) { tempFile },
        )
    }

    @Test
    fun `happy login succeeds and persists the entered server URL`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.onServerUrlChange("https://example.com/shoppinglist")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertTrue(viewModel.uiState.value.loginSucceeded)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals("https://example.com/shoppinglist/", serverConfig.serverUrl.first())
        assertTrue(repo.loginCalled)
        assertFalse(repo.registerCalled)
    }

    @Test
    fun `a successful login triggers an immediate sync so the first screen isn't empty`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val trigger = org.p23q.shoppinglist.data.sync.FakeSyncTrigger()
        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), trigger)

        viewModel.onServerUrlChange("https://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertEquals(1, trigger.immediateCount)
    }

    @Test
    fun `bad password surfaces an error message and does not succeed`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository(onLogin = { _, _ -> throw UnauthorizedException("bad creds") })
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.onServerUrlChange("https://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("wrong")
        viewModel.submit()?.join()

        assertFalse(viewModel.uiState.value.loginSucceeded)
        assertEquals(UiText.res(R.string.login_msg_incorrect_credentials), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `an untrusted certificate gets a distinct, actionable message (T-38)`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository(onLogin = { _, _ -> throw javax.net.ssl.SSLHandshakeException("cert") })
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.onServerUrlChange("https://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertFalse(viewModel.uiState.value.loginSucceeded)
        // Distinct from the generic offline/wrong-URL message.
        // Since T-111 the ViewModel names the message rather than rendering it, so this asserts
        // WHICH message was chosen — the wording itself now lives in strings.xml.
        val message = viewModel.uiState.value.errorMessage
        assertNotNull(message)
        assertEquals(UiText.res(R.string.login_msg_untrusted_cert), message)
        assertNotEquals(UiText.res(R.string.error_offline), message)
    }

    @Test
    fun `the self-signed toggle persists so it can apply before the first login (T-38)`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.setAllowSelfSignedCerts(true).join()

        assertTrue(viewModel.uiState.value.allowSelfSignedCerts)
        assertTrue(serverConfig.allowSelfSignedCerts.first())
    }

    @Test
    fun `register mode calls register before login`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.onServerUrlChange("https://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.onToggleRegisterMode()
        viewModel.submit()?.join()

        assertTrue(repo.registerCalled)
        assertTrue(repo.loginCalled)
    }

    @Test
    fun `non-https server URL is rejected before calling the repository`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.onServerUrlChange("http://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()

        assertFalse(repo.loginCalled)
        assertEquals(UiText.res(R.string.login_msg_invalid_url), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `logout delegates to the repository`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.logout().join()

        assertTrue(repo.loggedOut)
    }

    @Test
    fun `loggedInEmail reflects the session state's account email`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val sessionState = FakeSessionState().apply { accountEmail = "shopper@example.com" }
        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, sessionState, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        assertEquals("shopper@example.com", viewModel.loggedInEmail)
    }

    @Test
    fun `previously-saved server URL prefills the field`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        serverConfig.setServerUrl("https://saved.example.com/shoppinglist")

        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        // Await the init prefill coroutine's update rather than racing it.
        val prefilled = viewModel.uiState.first { it.serverUrl.isNotBlank() }
        assertEquals("https://saved.example.com/shoppinglist/", prefilled.serverUrl)
    }

    @Test
    fun `first run with nothing saved prefills the canonical instance URL`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = LoginViewModel(FakeAuthRepository(), newServerConfig(), FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        val prefilled = viewModel.uiState.first { it.serverUrl.isNotBlank() }

        assertEquals("https://p23q.org/shopping", prefilled.serverUrl)
    }

    @Test
    fun `prefill does not clobber a URL the user is already typing`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        serverConfig.setServerUrl("https://saved.example.com")

        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        viewModel.onServerUrlChange("https://typing.example.com")

        assertEquals("https://typing.example.com", viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `startDestinationAfterLogin resumes the last-opened list when present`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeAuthRepository(lastOpened = "list-42")
        val viewModel = LoginViewModel(repo, newServerConfig(), FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        assertEquals("list/list-42", viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `startDestinationAfterLogin falls back to overview with no last-opened list`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = LoginViewModel(FakeAuthRepository(), newServerConfig(), FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        assertEquals("overview", viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `a pending invite routes startDestinationAfterLogin into redeem and is consumed once`() = runTest(mainDispatcherRule.dispatcher) {
        val holder = org.p23q.shoppinglist.data.PendingInviteHolder().apply { stash("invite-xyz") }
        val repo = FakeAuthRepository(lastOpened = "list-42")
        val viewModel = LoginViewModel(repo, newServerConfig(), FakeSessionState(), holder, org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        // First resolution after login: resume the parked invite (T-28), overriding the last list.
        assertEquals("redeem/invite-xyz", viewModel.startDestinationAfterLogin())
        // Consumed — a second resolution falls back to the normal destination.
        assertEquals("list/list-42", viewModel.startDestinationAfterLogin())
    }
}
