package org.p23q.shoppinglist.ui.login

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.data.FakeCurrentAccount
import org.p23q.shoppinglist.core.AppTooOldException
import org.p23q.shoppinglist.core.NotATuppuServerException
import org.p23q.shoppinglist.core.ServerTooOldException
import org.p23q.shoppinglist.core.account.normalizeServerUrl
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.core.api.UnauthorizedException
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.UiText

class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuthRepository(
        private val onLogin: suspend (String, String) -> Unit = { _, _ -> },
        private val lastOpened: String? = null,
        private val registrationAllowed: Boolean = true,
    ) : AuthRepository {
        var registerCalled = false
        var loginCalled = false
        var loginUrl: String? = null
        var loginAllowedSelfSigned: Boolean? = null
        var loggedOut: String? = null
        var keptOnly: String? = null
        var loginKeptOthers: Boolean? = null
        /** How often the up-front registration check asked (T-287): must be zero on a fresh install. */
        var registrationChecks = 0

        override suspend fun register(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean) {
            registerCalled = true
        }

        override suspend fun login(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean, keepOtherAccounts: Boolean): String {
            loginCalled = true
            loginUrl = serverUrl
            loginAllowedSelfSigned = allowSelfSignedCerts
            loginKeptOthers = keepOtherAccounts
            onLogin(email, password)
            return "signed-in-account"
        }

        override suspend fun logout(accountId: String) {
            loggedOut = accountId
        }

        override suspend fun clearLocalSession(accountId: String) {}

        override suspend fun removeAccount(accountId: String) {}

        override suspend fun removeOtherAccounts(keep: String) {
            keptOnly = keep
        }

        override suspend fun registrationAllowed(serverUrl: String, allowSelfSignedCerts: Boolean): Boolean {
            registrationChecks++
            return registrationAllowed
        }

        override fun lastOpenedListId(): String? = lastOpened
    }

    /** No account yet: a first run. */
    private fun newServerConfig() = FakeCurrentAccount(localId = null)

    /** A returning user's account, signed out, on [url] (stored normalised, as accounts store it). */
    private fun FakeCurrentAccount.setServerUrl(url: String) {
        localId = TEST_ACCOUNT_ID
        serverUrl = normalizeServerUrl(url)
    }

    @Test
    fun `happy login succeeds and persists the entered server URL`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.onServerUrlChange("https://example.com/shoppinglist")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertTrue(viewModel.uiState.value.loginSucceeded)
        assertNull(viewModel.uiState.value.errorMessage)
        // The URL the user confirmed is the one signed in to; the account stores it (normalised).
        assertEquals("https://example.com/shoppinglist", repo.loginUrl)
        assertTrue(repo.loginCalled)
        assertFalse(repo.registerCalled)
    }

    @Test
    fun `a successful login triggers an immediate sync so the first screen isn't empty`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val trigger = org.p23q.shoppinglist.data.sync.FakeSyncTrigger()
        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), trigger)

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
        val viewModel = LoginViewModel(repo, serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

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
        val viewModel = LoginViewModel(repo, serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

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
        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.setAllowSelfSignedCerts(true).join()

        assertTrue(viewModel.uiState.value.allowSelfSignedCerts)
        assertTrue(serverConfig.allowSelfSignedCerts)
    }

    @Test
    fun `register mode calls register before login`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

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
        val viewModel = LoginViewModel(repo, serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.onServerUrlChange("http://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()

        assertFalse(repo.loginCalled)
        assertEquals(UiText.res(R.string.login_msg_invalid_url), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `logout delegates to the repository`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = FakeCurrentAccount()
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.logout().join()

        assertEquals(TEST_ACCOUNT_ID, repo.loggedOut)
    }

    @Test
    fun `loggedInEmail reflects the session state's account email`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val sessionState = FakeCurrentAccount().apply { accountEmail = "shopper@example.com" }
        val viewModel = LoginViewModel(FakeAuthRepository(), sessionState, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        assertEquals("shopper@example.com", viewModel.loggedInEmail)
    }

    @Test
    fun `registration status is fetched up front and disables registering when the server has it off (T-276)`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        // A returning user: a server was saved by an earlier login, so asking it is fine (T-287).
        serverConfig.setServerUrl("https://lists.example.com/")
        val repo = FakeAuthRepository(registrationAllowed = false)
        val viewModel = LoginViewModel(repo, serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        // Await the init coroutine's check rather than racing it, exactly like the prefill tests.
        val state = viewModel.uiState.first { !it.registrationAllowed }

        assertFalse(state.registrationAllowed)
    }

    @Test
    fun `a fresh install contacts no server before the first login (T-287)`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig() // nothing saved: first run
        val repo = FakeAuthRepository(registrationAllowed = false)
        val viewModel = LoginViewModel(repo, serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        // Await the prefill, then drain the scheduler: a check launched from init would run only
        // now, so asserting before this would pass whether or not one was made.
        viewModel.uiState.first { it.serverUrl.isNotBlank() }
        advanceUntilIdle()

        // Not asked, so still allowed — the register attempt failing is how a closed server says so —
        // and the prefilled default was not written anywhere either.
        assertEquals(0, repo.registrationChecks)
        assertTrue(viewModel.uiState.value.registrationAllowed)
        assertNull(serverConfig.serverUrl)
    }

    @Test
    fun `registration stays allowed by default, and after a server that cannot answer`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.refreshRegistrationStatus().join()

        assertTrue(viewModel.uiState.value.registrationAllowed)
    }

    @Test
    fun `previously-saved server URL prefills the field`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        serverConfig.setServerUrl("https://saved.example.com/shoppinglist")

        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        // Await the init prefill coroutine's update rather than racing it.
        val prefilled = viewModel.uiState.first { it.serverUrl.isNotBlank() }
        assertEquals("https://saved.example.com/shoppinglist/", prefilled.serverUrl)
    }

    @Test
    fun `first run with nothing saved prefills the canonical instance URL`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = LoginViewModel(FakeAuthRepository(), newServerConfig(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        val prefilled = viewModel.uiState.first { it.serverUrl.isNotBlank() }

        assertEquals("https://p23q.org/shopping", prefilled.serverUrl)
    }

    @Test
    fun `prefill does not clobber a URL the user is already typing`() = runTest(mainDispatcherRule.dispatcher) {
        val serverConfig = newServerConfig()
        serverConfig.setServerUrl("https://saved.example.com")

        val viewModel = LoginViewModel(FakeAuthRepository(), serverConfig, org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        viewModel.onServerUrlChange("https://typing.example.com")

        assertEquals("https://typing.example.com", viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `startDestinationAfterLogin resumes the last-opened list when present`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeAuthRepository(lastOpened = "list-42")
        val viewModel = LoginViewModel(repo, newServerConfig(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        assertEquals("list/list-42", viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `startDestinationAfterLogin falls back to overview with no last-opened list`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = LoginViewModel(FakeAuthRepository(), newServerConfig(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        assertEquals("overview", viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `a pending invite routes startDestinationAfterLogin into redeem and is consumed once`() = runTest(mainDispatcherRule.dispatcher) {
        val holder = org.p23q.shoppinglist.data.PendingInviteHolder().apply { stash("invite-xyz", null) }
        val repo = FakeAuthRepository(lastOpened = "list-42")
        val viewModel = LoginViewModel(repo, newServerConfig(), holder, org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        // First resolution after login: resume the parked invite (T-28), overriding the last list.
        assertEquals("redeem/invite-xyz", viewModel.startDestinationAfterLogin())
        // Consumed — a second resolution falls back to the normal destination.
        assertEquals("list/list-42", viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `a server below the protocol floor gets its own message (T-291)`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeAuthRepository(onLogin = { _, _ -> throw ServerTooOldException(2) })
        val viewModel = LoginViewModel(repo, newServerConfig(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.onServerUrlChange("https://old.example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertFalse(viewModel.uiState.value.loginSucceeded)
        assertEquals(UiText.res(R.string.login_msg_server_too_old), viewModel.uiState.value.errorMessage)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.submitWith(error: Throwable): LoginViewModel {
        val repo = FakeAuthRepository(onLogin = { _, _ -> throw error })
        val viewModel = LoginViewModel(repo, newServerConfig(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        viewModel.onServerUrlChange("https://new.example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()
        return viewModel
    }

    @Test
    fun `an app too old for the server gets its own message and the server's package (T-298)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = submitWith(AppTooOldException(4, "https://new.example.com/app.apk"))

        assertFalse(viewModel.uiState.value.loginSucceeded)
        assertEquals(UiText.res(R.string.login_msg_app_too_old), viewModel.uiState.value.errorMessage)
        assertEquals("https://new.example.com/app.apk", viewModel.uiState.value.downloadUrl)

        viewModel.onServerUrlChange("https://other.example.com")
        assertNull("another address is another question", viewModel.uiState.value.downloadUrl)
    }

    @Test
    fun `no Tuppu server at the address gets its own message (T-298)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = submitWith(NotATuppuServerException())

        assertEquals(UiText.res(R.string.login_msg_not_a_server), viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.downloadUrl)
    }

    /** T-298: this used to escape both catches and crash the login screen. */
    @Test
    fun `an answer that is not the API's JSON is no Tuppu server, not a crash (T-298)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = submitWith(kotlinx.serialization.SerializationException("Unexpected JSON token"))

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(UiText.res(R.string.login_msg_not_a_server), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `signing in asks the login to keep only the account that signed in (T-260, T-298)`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, newServerConfig(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.onServerUrlChange("https://example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertEquals(false, repo.loginKeptOthers)
        assertNull("the login removes the others itself, in the same step", repo.keptOnly)
    }

    @Test
    fun `the self-signed toggle is what the submit signs in with (T-38)`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeAuthRepository()
        val viewModel = LoginViewModel(repo, newServerConfig(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        viewModel.setAllowSelfSignedCerts(true).join()
        viewModel.onServerUrlChange("https://dev.example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertEquals(true, repo.loginAllowedSelfSigned)
    }
}
