package org.p23q.shoppinglist.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.data.AuthRepository
import org.p23q.shoppinglist.data.FakeSessionState
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.ui.login.LoginViewModel
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppDrawerScaffoldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class NoopAuthRepository : AuthRepository {
        var loggedOut = false
        override suspend fun register(email: String, password: String) {}
        override suspend fun login(email: String, password: String) {}
        override suspend fun logout() { loggedOut = true }
        override suspend fun clearLocalSession() {}
        override fun lastOpenedListId(): String? = null
    }

    private fun newServerConfig(): ServerConfig {
        val tempFile = File.createTempFile("drawer_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return ServerConfig(PreferenceDataStoreFactory.create { tempFile })
    }

    private fun setDrawerContent(loginViewModel: LoginViewModel): () -> NavHostController {
        lateinit var navController: NavHostController
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Routes.OVERVIEW) {
                composable(Routes.OVERVIEW) {
                    AppDrawerScaffold(navController = navController, title = "Overview", loginViewModel = loginViewModel) {
                        Text("Overview content")
                    }
                }
                composable(Routes.SETTINGS) { Text("Settings content") }
                composable(Routes.LOGIN) { Text("Login content") }
            }
        }
        return { navController }
    }

    @Test
    fun `selecting Account in the drawer navigates to settings`() {
        val sessionState = FakeSessionState()
        val loginViewModel = LoginViewModel(NoopAuthRepository(), newServerConfig(), sessionState)
        val getNavController = setDrawerContent(loginViewModel)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Account").performClick()

        assertEquals(Routes.SETTINGS, getNavController().currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `drawer shows the logged in account's email`() {
        val sessionState = FakeSessionState().apply { accountEmail = "shopper@example.com" }
        val loginViewModel = LoginViewModel(NoopAuthRepository(), newServerConfig(), sessionState)
        setDrawerContent(loginViewModel)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        composeTestRule.onNodeWithText("shopper@example.com").assertExists()
    }

    @Test
    fun `logging out clears the session and navigates back to login`() {
        val sessionState = FakeSessionState().apply { accountEmail = "shopper@example.com" }
        val authRepository = NoopAuthRepository()
        val loginViewModel = LoginViewModel(authRepository, newServerConfig(), sessionState)
        val getNavController = setDrawerContent(loginViewModel)

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Log out").performClick()
        composeTestRule.waitForIdle()

        assertEquals(Routes.LOGIN, getNavController().currentBackStackEntry?.destination?.route)
        assertEquals(true, authRepository.loggedOut)
    }
}
