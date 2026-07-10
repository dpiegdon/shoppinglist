package org.p23q.shoppinglist.ui.login

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
        return ServerConfig(PreferenceDataStoreFactory.create { tempFile })
    }

    @Test
    fun `happy login succeeds and persists the entered server URL`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())

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
    fun `bad password surfaces an error message and does not succeed`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository(onLogin = { _, _ -> throw UnauthorizedException("bad creds") })
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())

        viewModel.onServerUrlChange("https://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("wrong")
        viewModel.submit()?.join()

        assertFalse(viewModel.uiState.value.loginSucceeded)
        assertEquals("Incorrect email or password", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `an untrusted certificate gets a distinct, actionable message (T-38)`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository(onLogin = { _, _ -> throw javax.net.ssl.SSLHandshakeException("cert") })
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())

        viewModel.onServerUrlChange("https://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertFalse(viewModel.uiState.value.loginSucceeded)
        // Distinct from the generic offline/wrong-URL message.
        val message = viewModel.uiState.value.errorMessage
        assertNotNull(message)
        assertTrue(message!!.contains("certificate"))
        assertNotEquals("Couldn't reach the server", message)
    }

    @Test
    fun `the self-signed toggle persists so it can apply before the first login (T-38)`() = runTest {
        val serverConfig = newServerConfig()
        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())

        viewModel.setAllowSelfSignedCerts(true).join()

        assertTrue(viewModel.uiState.value.allowSelfSignedCerts)
        assertTrue(serverConfig.allowSelfSignedCerts.first())
    }

    @Test
    fun `register mode calls register before login`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())

        viewModel.onServerUrlChange("https://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.onToggleRegisterMode()
        viewModel.submit()?.join()

        assertTrue(repo.registerCalled)
        assertTrue(repo.loginCalled)
    }

    @Test
    fun `non-https server URL is rejected before calling the repository`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())

        viewModel.onServerUrlChange("http://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()

        assertFalse(repo.loginCalled)
        assertEquals("Enter a valid https server URL", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `logout delegates to the repository`() = runTest {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())

        viewModel.logout().join()

        assertTrue(repo.loggedOut)
    }

    @Test
    fun `loggedInEmail reflects the session state's account email`() = runTest {
        val serverConfig = newServerConfig()
        val sessionState = FakeSessionState().apply { accountEmail = "shopper@example.com" }
        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, sessionState, org.p23q.shoppinglist.data.PendingInviteHolder())

        assertEquals("shopper@example.com", viewModel.loggedInEmail)
    }

    @Test
    fun `previously-saved server URL prefills the field`() = runTest {
        val serverConfig = newServerConfig()
        serverConfig.setServerUrl("https://saved.example.com/shoppinglist")

        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())

        // Await the init prefill coroutine's update rather than racing it.
        val prefilled = viewModel.uiState.first { it.serverUrl.isNotBlank() }
        assertEquals("https://saved.example.com/shoppinglist/", prefilled.serverUrl)
    }

    @Test
    fun `prefill does not clobber a URL the user is already typing`() = runTest {
        val serverConfig = newServerConfig()
        serverConfig.setServerUrl("https://saved.example.com")

        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())
        viewModel.onServerUrlChange("https://typing.example.com")

        assertEquals("https://typing.example.com", viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `startDestinationAfterLogin resumes the last-opened list when present`() = runTest {
        val repo = FakeAuthRepository(lastOpened = "list-42")
        val viewModel = LoginViewModel(repo, newServerConfig(), FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())

        assertEquals("list/list-42", viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `startDestinationAfterLogin falls back to overview with no last-opened list`() = runTest {
        val viewModel = LoginViewModel(FakeAuthRepository(), newServerConfig(), FakeSessionState(), org.p23q.shoppinglist.data.PendingInviteHolder())

        assertEquals("overview", viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `a pending invite routes startDestinationAfterLogin into redeem and is consumed once`() = runTest {
        val holder = org.p23q.shoppinglist.data.PendingInviteHolder().apply { stash("invite-xyz") }
        val repo = FakeAuthRepository(lastOpened = "list-42")
        val viewModel = LoginViewModel(repo, newServerConfig(), FakeSessionState(), holder)

        // First resolution after login: resume the parked invite (T-28), overriding the last list.
        assertEquals("redeem/invite-xyz", viewModel.startDestinationAfterLogin())
        // Consumed — a second resolution falls back to the normal destination.
        assertEquals("list/list-42", viewModel.startDestinationAfterLogin())
    }
}
