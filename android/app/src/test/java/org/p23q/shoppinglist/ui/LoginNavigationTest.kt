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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.ui.accounts.AccountGone
import org.p23q.shoppinglist.ui.login.LoginMode
import org.robolectric.RobolectricTestRunner

/** Where the login form and the Account screen lead (T-300), over the app's routes. */
@RunWith(RobolectricTestRunner::class)
class LoginNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun host(start: String = Routes.LOGIN_PATTERN): NavHostController {
        lateinit var navController: NavHostController
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = start) {
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
                        "login mode=${LoginMode.fromArg(args?.getString(Routes.LOGIN_MODE_ARG))} " +
                            "account=${args?.getString(Routes.ACCOUNT_ID_ARG)} url=${args?.getString(Routes.SERVER_URL_ARG)}",
                    )
                }
                composable(Routes.OVERVIEW) { Text("overview") }
                composable(Routes.ACCOUNTS) { Text("accounts") }
                composable(Routes.ACCOUNT_PATTERN) { Text("account") }
                composable(Routes.LIST_PATTERN) { entry -> Text("list ${entry.arguments?.getString(Routes.LIST_ID_ARG)}") }
                composable(Routes.REDEEM_PATTERN) { Text("redeem") }
            }
        }
        return navController
    }

    @Test
    fun `removing the last account opens an empty start screen with nothing behind it`() {
        val nav = host(start = Routes.OVERVIEW)
        composeTestRule.runOnIdle {
            nav.navigate(Routes.ACCOUNTS)
            nav.navigate(Routes.account("a"))
        }

        composeTestRule.runOnIdle { nav.afterAccountGone(AccountGone.TO_START) }

        // Not "{serverUrl}", which navigating to the pattern itself put in the form.
        composeTestRule.onNodeWithText("login mode=START account=null url=null").assertExists()
        composeTestRule.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    @Test
    fun `removing one of several accounts goes back to the Accounts screen`() {
        val nav = host(start = Routes.OVERVIEW)
        composeTestRule.runOnIdle {
            nav.navigate(Routes.ACCOUNTS)
            nav.navigate(Routes.account("a"))
        }

        composeTestRule.runOnIdle { nav.afterAccountGone(AccountGone.TO_ACCOUNTS) }

        composeTestRule.onNodeWithText("accounts").assertExists()
        composeTestRule.runOnIdle { assertEquals(Routes.OVERVIEW, nav.previousBackStackEntry?.destination?.route) }
    }

    /**
     * T-300: an invite tapped with no account put a second start screen over the first; the
     * sign-in removed only that one, and Back from the joined list opened the form again.
     */
    @Test
    fun `the start screen opened for an invite leaves no login form behind the redeem`() {
        val nav = host()
        composeTestRule.runOnIdle { nav.navigate(Routes.redeem("tok")) }
        // As the redeem screen sends the user to sign in.
        composeTestRule.runOnIdle {
            nav.navigate(Routes.login(LoginMode.START)) {
                popUpTo(Routes.REDEEM_PATTERN) { inclusive = true }
            }
        }

        composeTestRule.runOnIdle { nav.afterLogin(LoginMode.START, Routes.redeem("tok")) }

        composeTestRule.onNodeWithText("redeem").assertExists()
        composeTestRule.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    @Test
    fun `an added account's sign-in without a destination goes back where the form was opened`() {
        val nav = host(start = Routes.OVERVIEW)
        composeTestRule.runOnIdle {
            nav.navigate(Routes.ACCOUNTS)
            nav.navigate(Routes.login(LoginMode.ADD))
        }

        composeTestRule.runOnIdle { nav.afterLogin(LoginMode.ADD, null) }

        composeTestRule.onNodeWithText("accounts").assertExists()
    }

    @Test
    fun `an added account's sign-in into a redeem replaces the form only`() {
        val nav = host(start = Routes.OVERVIEW)
        composeTestRule.runOnIdle { nav.navigate(Routes.login(LoginMode.ADD)) }

        composeTestRule.runOnIdle { nav.afterLogin(LoginMode.ADD, Routes.redeem("tok")) }

        composeTestRule.onNodeWithText("redeem").assertExists()
        composeTestRule.runOnIdle { assertEquals(Routes.OVERVIEW, nav.previousBackStackEntry?.destination?.route) }
    }
}
