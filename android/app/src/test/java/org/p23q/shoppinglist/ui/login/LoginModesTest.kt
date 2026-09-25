package org.p23q.shoppinglist.ui.login

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import org.p23q.shoppinglist.core.AlreadyAddedException
import org.p23q.shoppinglist.core.LoginExpectation
import org.p23q.shoppinglist.core.WrongAccountException
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

    private class Repo(private val signsInAs: String, private val refusal: Exception? = null) : RecordingAuthRepository() {
        var loginUrl: String? = null
        var expected: LoginExpectation? = null

        override suspend fun login(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean, expect: LoginExpectation): String {
            loginUrl = serverUrl
            expected = expect
            refusal?.let { throw it }
            return signsInAs
        }

        /** The addresses asked whether they accept new accounts. */
        val registrationChecks = mutableListOf<String>()

        override suspend fun registrationAllowed(serverUrl: String, allowSelfSignedCerts: Boolean): Boolean {
            registrationChecks += serverUrl
            return true
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
        assertEquals(LoginExpectation.NewAccount, repo.expected)
        assertTrue(repo.removed.isEmpty())
        // Null: back to the Accounts screen, not on to the overview.
        assertNull(viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `add mode opened for an invite's server prefills that server, and redeems after signing in`() = runTest(mainDispatcherRule.dispatcher) {
        pendingInvites.stash("invite-xyz", null, null, LoginMode.ADD)
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
        val viewModel = viewModel(Repo(signsInAs = "prod", refusal = AlreadyAddedException()), Routes.LOGIN_MODE_ARG to LoginMode.ADD.arg)
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
        assertEquals(LoginExpectation.Account("stage"), repo.expected)
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
    fun `a re-sign-in with another account's credentials says so (T-300)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(
            Repo(signsInAs = "other", refusal = WrongAccountException()),
            Routes.LOGIN_MODE_ARG to LoginMode.RESIGNIN.arg,
            Routes.ACCOUNT_ID_ARG to "stage",
        )
        viewModel.onPasswordChange("hunter2")

        viewModel.submit()?.join()

        assertFalse(viewModel.uiState.value.loginSucceeded)
        assertEquals(UiText.res(R.string.login_msg_wrong_account), viewModel.uiState.value.errorMessage)
    }

    /** T-300: 3.1.0's last typed address may not be the server of the session it migrated. */
    @Test
    fun `a re-sign-in for a row with no recorded account leaves its server editable`() = runTest(mainDispatcherRule.dispatcher) {
        known = listOf(prod, stage.copy(accountId = null))
        val repo = Repo(signsInAs = "stage")
        val viewModel = viewModel(repo, Routes.LOGIN_MODE_ARG to LoginMode.RESIGNIN.arg, Routes.ACCOUNT_ID_ARG to "stage")

        assertFalse(viewModel.uiState.value.serverUrlLocked)
        viewModel.onServerUrlChange("https://real.example.test/")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertEquals("https://real.example.test/", repo.loginUrl)
        assertEquals(LoginExpectation.Account("stage"), repo.expected)
    }

    /** T-300: the check asked the last typed server while the form showed the invite's. */
    @Test
    fun `add mode opened for an invite asks no server about registration before one is confirmed`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = Repo("new")
        viewModel(repo, Routes.LOGIN_MODE_ARG to LoginMode.ADD.arg, Routes.SERVER_URL_ARG to "https://invite.example.test/lists/")

        advanceUntilIdle()

        assertEquals(emptyList<String>(), repo.registrationChecks)
    }

    @Test
    fun `the registration check asks the form's address once it has been submitted`() = runTest(mainDispatcherRule.dispatcher) {
        serverConfig.url = "https://invite.example.test/lists/"
        val repo = Repo("new")
        viewModel(repo, Routes.LOGIN_MODE_ARG to LoginMode.ADD.arg, Routes.SERVER_URL_ARG to "https://invite.example.test/lists/")

        advanceUntilIdle()

        assertEquals(listOf("https://invite.example.test/lists/"), repo.registrationChecks)
    }

    /** T-300: the redeem matched the link's prefix again and looped back to "already added". */
    @Test
    fun `an invite parked for an added account resumes into the account that signed in`() = runTest(mainDispatcherRule.dispatcher) {
        val link = "https://invite.example.test/lists/invite/invite-xyz"
        pendingInvites.stash("invite-xyz", link, null, LoginMode.ADD)
        val viewModel = viewModel(Repo("new"), Routes.LOGIN_MODE_ARG to LoginMode.ADD.arg, Routes.SERVER_URL_ARG to "https://invite.example.test/lists/")
        viewModel.uiState.first { it.serverUrl.isNotBlank() }
        viewModel.fillIn()

        viewModel.submit()?.join()

        assertEquals(Routes.redeem("invite-xyz", link, "new"), viewModel.startDestinationAfterLogin())
    }

    /** A view model that can be cleared, as leaving its screen clears it. */
    private fun clearable(repo: Repo, vararg args: Pair<String, String>): Pair<LoginViewModel, ViewModelStore> {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel(repo, *args) as T
        }
        return ViewModelProvider(store, factory)[LoginViewModel::class.java] to store
    }

    /** T-300: a parked invite outlived the form and took over the next, unrelated sign-in. */
    @Test
    fun `leaving the form without signing in drops the parked invite`() = runTest(mainDispatcherRule.dispatcher) {
        pendingInvites.stash("invite-xyz", null, "stage", LoginMode.RESIGNIN)
        val (_, store) = clearable(Repo("stage"), Routes.LOGIN_MODE_ARG to LoginMode.RESIGNIN.arg, Routes.ACCOUNT_ID_ARG to "stage")

        store.clear()

        assertNull(pendingInvites.consumeFor(LoginMode.RESIGNIN, "stage"))
    }

    @Test
    fun `signing in keeps the parked invite for the destination after it`() = runTest(mainDispatcherRule.dispatcher) {
        pendingInvites.stash("invite-xyz", null, "stage", LoginMode.RESIGNIN)
        val (viewModel, store) = clearable(Repo("stage"), Routes.LOGIN_MODE_ARG to LoginMode.RESIGNIN.arg, Routes.ACCOUNT_ID_ARG to "stage")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        store.clear()

        assertEquals(Routes.redeem("invite-xyz", null, "stage"), viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `an invite parked for another account's re-sign-in is not resumed`() = runTest(mainDispatcherRule.dispatcher) {
        pendingInvites.stash("invite-xyz", null, "prod", LoginMode.RESIGNIN)
        val viewModel = viewModel(Repo("stage"), Routes.LOGIN_MODE_ARG to LoginMode.RESIGNIN.arg, Routes.ACCOUNT_ID_ARG to "stage")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()?.join()

        assertNull(viewModel.startDestinationAfterLogin())
    }

    @Test
    fun `an invite parked for a re-sign-in is not resumed by the start screen`() = runTest(mainDispatcherRule.dispatcher) {
        pendingInvites.stash("invite-xyz", null, "stage", LoginMode.RESIGNIN)
        val viewModel = viewModel(Repo("new"))

        assertEquals("list/list-42", viewModel.startDestinationAfterLogin())
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
