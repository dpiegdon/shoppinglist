package org.p23q.shoppinglist.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.ui.login.LoginMode
import org.robolectric.RobolectricTestRunner

/** The login route's optional arguments (T-292): the plain "login" others navigate to still lands on it. */
@RunWith(RobolectricTestRunner::class)
class LoginRouteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun host(): NavHostController {
        lateinit var navController: NavHostController
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Routes.LOGIN_PATTERN) {
                composable(
                    Routes.LOGIN_PATTERN,
                    arguments = listOf(Routes.LOGIN_MODE_ARG, Routes.ACCOUNT_ID_ARG, Routes.SERVER_URL_ARG).map {
                        navArgument(it) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    },
                ) { entry ->
                    val args = entry.arguments
                    Text(
                        "mode=${LoginMode.fromArg(args?.getString(Routes.LOGIN_MODE_ARG))} " +
                            "account=${args?.getString(Routes.ACCOUNT_ID_ARG)} url=${args?.getString(Routes.SERVER_URL_ARG)}",
                    )
                }
                composable(Routes.OVERVIEW) { Text("overview") }
            }
        }
        return navController
    }

    @Test
    fun `the pattern as start destination is the start screen`() {
        host()
        composeTestRule.onNodeWithText("mode=START account=null url=null").assertExists()
    }

    @Test
    fun `the builders and the plain route reach the form with their arguments`() {
        val nav = host()

        composeTestRule.runOnIdle { nav.navigate(Routes.login(LoginMode.RESIGNIN, accountId = "stage")) }
        composeTestRule.onNodeWithText("mode=RESIGNIN account=stage url=null").assertExists()

        composeTestRule.runOnIdle { nav.navigate(Routes.login(LoginMode.ADD, serverUrl = "https://a.example/b c/")) }
        composeTestRule.onNodeWithText("mode=ADD account=null url=https://a.example/b c/").assertExists()

        composeTestRule.runOnIdle {
            nav.navigate(Routes.LOGIN) {
                popUpTo(Routes.LOGIN) { inclusive = true }
                launchSingleTop = true
            }
        }
        composeTestRule.onNodeWithText("mode=START account=null url=null").assertExists()
    }
}
