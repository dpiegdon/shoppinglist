package org.p23q.shoppinglist.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.data.FakeCurrentAccount
import org.p23q.shoppinglist.ui.login.LoginViewModel
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDrawerScaffoldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class NoopAuthRepository : AuthRepository {
        var loggedOut = false
        override suspend fun register(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean) {}
        override suspend fun login(serverUrl: String, email: String, password: String, allowSelfSignedCerts: Boolean, keepOtherAccounts: Boolean) = ""
        override suspend fun logout(accountId: String) { loggedOut = true }
        override suspend fun clearLocalSession(accountId: String) {}
        override suspend fun removeAccount(accountId: String) {}
        override suspend fun removeOtherAccounts(keep: String) {}
        override suspend fun registrationAllowed(serverUrl: String, allowSelfSignedCerts: Boolean): Boolean = true
        override fun lastOpenedListId(): String? = null
    }

    private fun setDrawerContent(loginViewModel: LoginViewModel): () -> NavHostController {
        lateinit var navController: NavHostController
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Routes.OVERVIEW) {
                composable(Routes.OVERVIEW) {
                    AppDrawerScaffold(
                        navController = navController,
                        title = "Overview",
                        loginViewModel = loginViewModel,
                        syncStatusViewModel = SyncStatusViewModel(SyncStatus()),
                    ) {
                        Text("Overview content")
                    }
                }
                composable(Routes.SETTINGS) { Text("Settings content") }
                composable(Routes.ADMIN) { Text("Admin content") }
                composable(Routes.ABOUT) { Text("About content") }
                composable(Routes.LOGIN) { Text("Login content") }
            }
        }
        return { navController }
    }

    @Test
    fun `selecting Settings in the drawer navigates to settings`() {
        val sessionState = FakeCurrentAccount()
        val loginViewModel = LoginViewModel(NoopAuthRepository(), sessionState, org.p23q.shoppinglist.data.FakeLastServerAddress(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        val getNavController = setDrawerContent(loginViewModel)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        // Labelled for the screen it opens (T-170); it said "Account".
        composeTestRule.onNodeWithText("Settings").performClick()

        assertEquals(Routes.SETTINGS, getNavController().currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `drawer shows the logged in account's email`() {
        val sessionState = FakeCurrentAccount().apply { accountEmail = "shopper@example.com" }
        val loginViewModel = LoginViewModel(NoopAuthRepository(), sessionState, org.p23q.shoppinglist.data.FakeLastServerAddress(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        setDrawerContent(loginViewModel)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        composeTestRule.onNodeWithText("shopper@example.com").assertExists()
    }

    @Test
    fun `logging out clears the session and navigates back to login`() {
        val sessionState = FakeCurrentAccount().apply { accountEmail = "shopper@example.com" }
        val authRepository = NoopAuthRepository()
        val loginViewModel = LoginViewModel(authRepository, sessionState, org.p23q.shoppinglist.data.FakeLastServerAddress(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        val getNavController = setDrawerContent(loginViewModel)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Log out").performClick()
        composeTestRule.waitForIdle()

        assertEquals(Routes.LOGIN, getNavController().currentBackStackEntry?.destination?.route)
        assertEquals(true, authRepository.loggedOut)
    }

    @Test
    fun `an admin gets a Server admin entry right after Settings (T-220)`() {
        val sessionState = FakeCurrentAccount().apply { isAdmin = true }
        val loginViewModel = LoginViewModel(NoopAuthRepository(), sessionState, org.p23q.shoppinglist.data.FakeLastServerAddress(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        val getNavController = setDrawerContent(loginViewModel)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        // Directly after Settings, before Log out — by where the rows actually sit in the sheet.
        val y = { text: String -> composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y }
        assertTrue(y("Settings") < y("Server admin"))
        assertTrue(y("Server admin") < y("Log out"))

        composeTestRule.onNodeWithText("Server admin").performClick()
        assertEquals(Routes.ADMIN, getNavController().currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `a non-admin is not offered the Server admin entry (T-220)`() {
        // An affordance only — the server refuses every admin route whatever the drawer shows.
        val loginViewModel = LoginViewModel(NoopAuthRepository(), FakeCurrentAccount(), org.p23q.shoppinglist.data.FakeLastServerAddress(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        setDrawerContent(loginViewModel)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        composeTestRule.onNodeWithText("Settings").assertExists()
        composeTestRule.onNodeWithText("Server admin").assertDoesNotExist()
    }

    @Test
    fun `the top bar carries the sync status on every screen (T-178)`() {
        val loginViewModel = LoginViewModel(NoopAuthRepository(), FakeCurrentAccount(), org.p23q.shoppinglist.data.FakeLastServerAddress(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        setDrawerContent(loginViewModel)

        // A fresh SyncStatus has never synced: the dot says so in its description.
        composeTestRule.onNodeWithContentDescription("Not synced yet").assertExists()
    }

    @Test
    fun `About is the last entry before Log out and opens the About screen (T-224)`() {
        val loginViewModel = LoginViewModel(NoopAuthRepository(), FakeCurrentAccount(), org.p23q.shoppinglist.data.FakeLastServerAddress(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        val getNavController = setDrawerContent(loginViewModel)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        // After Settings, before Log out — by where the rows actually sit in the sheet.
        val y = { text: String -> composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y }
        assertTrue(y("Settings") < y("About"))
        assertTrue(y("About") < y("Log out"))

        composeTestRule.onNodeWithText("About").performClick()
        assertEquals(Routes.ABOUT, getNavController().currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `an admin sees Server admin between Settings and About (T-220, T-224)`() {
        val sessionState = FakeCurrentAccount().apply { isAdmin = true }
        val loginViewModel = LoginViewModel(NoopAuthRepository(), sessionState, org.p23q.shoppinglist.data.FakeLastServerAddress(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.sync.FakeSyncTrigger())
        setDrawerContent(loginViewModel)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        val y = { text: String -> composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y }
        assertTrue(y("Server admin") < y("About"))
        assertTrue(y("About") < y("Log out"))
    }
}
