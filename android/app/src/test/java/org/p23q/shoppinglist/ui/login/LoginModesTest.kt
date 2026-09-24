package org.p23q.shoppinglist.ui.login

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.FakeLastServerAddress
import org.p23q.shoppinglist.data.PendingInviteHolder
import org.p23q.shoppinglist.data.RecordingAuthRepository
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.testAccount
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.UiText

/** The login form's three uses (T-292): the start screen, adding an account, signing one in again. */
class LoginModesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Repo(private val signsInAs: String) : RecordingAuthRepository() {
        var loginUrl: String? = null
        var keptOthers: Boolean? = null

        override suspend fun login(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean, keepOtherAccounts: Boolean): String {
            loginUrl = serverUrl
            keptOthers = keepOtherAccounts
            return signsInAs
        }

        override fun lastOpenedListId(): String? = "list-42"
    }

    private val prod = testAccount(id = "prod", serverUrl = "https://lists.example.test/", email = "me@example.com")
    private val stage = testAccount(id = "stage", serverUrl = "https://lists.example.test/stage/", email = "me@example.com", signedIn = false)
        .copy(allowSelfSignedCerts = true)
    private var known = listOf(prod, stage)
    private val serverConfig = FakeLastServerAddress(url = "https://typed.example.test/")
    private val pendingInvites = PendingInviteHolder()

    private fun viewModel(repo: Repo, vararg args: Pair<String, String>) = LoginViewModel(
        repo,
        KnownAccounts { known },
        serverConfig,
        pendingInvites,
        FakeSyncTrigger(),
        SavedStateHandle(mapOf(*args)),
    )

    private fun LoginViewModel.fillIn(email: String = "me@example.com") {
        onEmailChange(email)
        onPasswordChange("hunter2")
    }

    @Test
    fun `no mode is the start screen, which goes to the lists after signing in`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(Repo("new"))

        assertEquals(LoginMode.START, viewModel.uiState.value.mode)
        assertEquals("list/list-42", viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `add mode prefills the last typed address, keeps the others and returns where it came from`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = Repo("new")
        val viewModel = viewModel(repo, Routes.LOGIN_MODE_ARG to LoginMode.ADD.arg)

        assertEquals("https://typed.example.test/", viewModel.uiState.first { it.serverUrl.isNotBlank() }.serverUrl)
        viewModel.fillIn(email = "other@example.com")
        viewModel.submit()?.join()

        assertTrue(viewModel.uiState.value.loginSucceeded)
        assertEquals(true, repo.keptOthers)
        assertTrue(repo.removed.isEmpty())
        // Null: back to the Accounts screen, not on to the overview.
        assertNull(viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `add mode opened for an invite's server prefills that server, and redeems after signing in`() = runTest(mainDispatcherRule.dispatcher) {
        pendingInvites.stash("invite-xyz", null)
        val viewModel = viewModel(
            Repo("new"),
            Routes.LOGIN_MODE_ARG to LoginMode.ADD.arg,
            Routes.SERVER_URL_ARG to "https://invite.example.test/lists/",
        )

        // The invite's server, not the last typed one.
        viewModel.uiState.first { it.allowSelfSignedCerts == serverConfig.allowSelfSigned }
        assertEquals("https://invite.example.test/lists/", viewModel.uiState.value.serverUrl)
        assertEquals("redeem/invite-xyz", viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `adding an account that is already here and signed in is refused with a message`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(Repo(signsInAs = "prod"), Routes.LOGIN_MODE_ARG to LoginMode.ADD.arg)
        viewModel.uiState.first { it.serverUrl.isNotBlank() }
        viewModel.onServerUrlChange("https://lists.example.test/")
        viewModel.fillIn()

        viewModel.submit()?.join()

        assertFalse(viewModel.uiState.value.loginSucceeded)
        assertEquals(UiText.res(R.string.login_msg_already_added), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `adding an account that is here but signed out just signs it in again`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(Repo(signsInAs = "stage"), Routes.LOGIN_MODE_ARG to LoginMode.ADD.arg)
        viewModel.uiState.first { it.serverUrl.isNotBlank() }
        viewModel.fillIn()

        viewModel.submit()?.join()

        assertTrue(viewModel.uiState.value.loginSucceeded)
    }

    @Test
    fun `re-sign-in prefills the account's server, email and certificate choice, and the server cannot change`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = Repo(signsInAs = "stage")
        val viewModel = viewModel(repo, Routes.LOGIN_MODE_ARG to LoginMode.RESIGNIN.arg, Routes.ACCOUNT_ID_ARG to "stage")

        val state = viewModel.uiState.value
        assertEquals(LoginMode.RESIGNIN, state.mode)
        assertEquals("https://lists.example.test/stage/", state.serverUrl)
        assertEquals("me@example.com", state.email)
        assertTrue(state.serverUrlLocked)
        assertTrue(state.allowSelfSignedCerts)

        viewModel.onServerUrlChange("https://elsewhere.example.test/")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertEquals("https://lists.example.test/stage/", repo.loginUrl)
        assertTrue(viewModel.uiState.value.loginSucceeded)
        assertEquals(true, repo.keptOthers)
        assertNull(viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `re-sign-in is not answered from the last typed address`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(Repo("stage"), Routes.LOGIN_MODE_ARG to LoginMode.RESIGNIN.arg, Routes.ACCOUNT_ID_ARG to "stage")

        // The init's prefill would have replaced a blank URL only; here it must not run at all.
        advanceUntilIdle()
        assertEquals("https://lists.example.test/stage/", viewModel.uiState.value.serverUrl)
        assertTrue(viewModel.uiState.value.allowSelfSignedCerts)
    }

    @Test
    fun `route builders carry the mode, the account and an encoded URL`() {
        assertEquals("login?mode=add", Routes.login(LoginMode.ADD))
        assertEquals("login?mode=resignin&accountId=stage", Routes.login(LoginMode.RESIGNIN, accountId = "stage"))
        assertEquals(
            "login?mode=add&serverUrl=https%3A%2F%2Fa.example%2Fb%20c%2F",
            Routes.login(LoginMode.ADD, serverUrl = "https://a.example/b c/"),
        )
    }
}
