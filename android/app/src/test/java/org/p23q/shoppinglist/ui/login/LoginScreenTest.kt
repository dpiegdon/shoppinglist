package org.p23q.shoppinglist.ui.login

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.p23q.shoppinglist.core.AppTooOldException
import org.p23q.shoppinglist.core.NotATuppuServerException
import org.p23q.shoppinglist.core.api.PROTOCOL_VERSION
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.data.FakeCurrentAccount
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class NoopAuthRepository(
        private val registrationAllowed: Boolean = true,
        private val onLogin: () -> Unit = {},
    ) : AuthRepository {
        override suspend fun register(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean) {}
        override suspend fun login(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean, keepOtherAccounts: Boolean): String {
            onLogin()
            return ""
        }
        override suspend fun logout(accountId: String) {}
        override suspend fun clearLocalSession(accountId: String) {}
        override suspend fun removeAccount(accountId: String) {}
        override suspend fun removeOtherAccounts(keep: String) {}
        override suspend fun registrationAllowed(serverUrl: String, allowSelfSignedCerts: Boolean): Boolean = registrationAllowed
        override fun lastOpenedListId(): String? = null
    }

    @Test
    fun `renders server URL, email, password fields and a submit button`() {
        val viewModel = LoginViewModel(NoopAuthRepository(), FakeCurrentAccount(localId = null), org.p23q.shoppinglist.data.FakeLastServerAddress(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        composeTestRule.setContent {
            LoginScreen(onLoginSuccess = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("Server URL").assertExists()
        composeTestRule.onNodeWithText("Email").assertExists()
        composeTestRule.onNodeWithText("Password").assertExists()
        composeTestRule.onNodeWithText("Log in").assertExists()
    }

    @Test
    fun `carries the name in cuneiform under the title, uncaptioned (T-225)`() {
        val viewModel = LoginViewModel(NoopAuthRepository(), FakeCurrentAccount(localId = null), org.p23q.shoppinglist.data.FakeLastServerAddress(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())

        composeTestRule.setContent {
            LoginScreen(onLoginSuccess = {}, viewModel = viewModel)
        }

        composeTestRule
            .onNodeWithTag("login-cuneiform", useUnmergedTree = true)
            .assertExists()
            .assertContentDescriptionEquals("ṭuppu")
        // No caption here — the reading is spelled out on About, not on the way in.
        composeTestRule.onNodeWithText("ṭuppu").assertDoesNotExist()
    }

    @Test
    fun `a server that has registration off says so up front and disables the toggle (T-276)`() {
        // A saved server, as after an earlier submit: on a fresh install nothing is asked (T-287).
        val serverConfig = org.p23q.shoppinglist.data.FakeLastServerAddress(url = "https://lists.example.com/")
        val viewModel = LoginViewModel(
            NoopAuthRepository(registrationAllowed = false),
            FakeCurrentAccount(localId = null),
            serverConfig,
            org.p23q.shoppinglist.data.PendingInviteHolder(),
            org.p23q.shoppinglist.data.sync.FakeSyncTrigger(),
        )

        composeTestRule.setContent {
            LoginScreen(onLoginSuccess = {}, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Registration is disabled on this server.").assertExists()
        composeTestRule.onNodeWithText("New here? Register").assertIsNotEnabled()
    }

    private fun submitAgainst(repository: AuthRepository, onDownload: (String) -> Unit = {}) {
        val viewModel = LoginViewModel(
            repository,
            FakeCurrentAccount(localId = null),
            org.p23q.shoppinglist.data.FakeLastServerAddress(),
            org.p23q.shoppinglist.data.PendingInviteHolder(),
            org.p23q.shoppinglist.data.sync.FakeSyncTrigger(),
        )
        composeTestRule.setContent {
            LoginScreen(onLoginSuccess = {}, viewModel = viewModel, onDownload = onDownload)
        }
        viewModel.onServerUrlChange("https://new.example.com")
        viewModel.onEmailChange("milk@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()
        composeTestRule.waitForIdle()
    }

    /** T-298: a server newer than this build; its package is offered right on the login screen. */
    @Test
    fun `an app too old for the server says so and offers the server's package`() {
        val opened = mutableListOf<String>()
        submitAgainst(
            NoopAuthRepository(onLogin = { throw AppTooOldException(PROTOCOL_VERSION + 1, "https://new.example.com/app.apk") }),
            onDownload = { opened += it },
        )

        composeTestRule.onNodeWithText("This app is too old for this server.").assertExists()
        composeTestRule.onNodeWithTag("login-download").assertTextEquals("Update").performScrollTo().performClick()
        assertEquals(listOf("https://new.example.com/app.apk"), opened)
    }

    @Test
    fun `an app too old for a server with no package offers no download`() {
        submitAgainst(NoopAuthRepository(onLogin = { throw AppTooOldException(PROTOCOL_VERSION + 1, null) }))

        composeTestRule.onNodeWithText("This app is too old for this server.").assertExists()
        composeTestRule.onNodeWithTag("login-download").assertDoesNotExist()
    }

    @Test
    fun `an address where no Tuppu server answered says so`() {
        submitAgainst(NoopAuthRepository(onLogin = { throw NotATuppuServerException() }))

        composeTestRule.onNodeWithText("No Tuppu server answered at this address.").assertExists()
    }
}
