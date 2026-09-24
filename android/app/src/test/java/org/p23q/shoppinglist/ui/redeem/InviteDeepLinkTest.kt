package org.p23q.shoppinglist.ui.redeem

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.ui.INVITE_LINK_PATTERNS
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.inviteLinkOf
import org.robolectric.RobolectricTestRunner

/**
 * A tapped invite link reaches the redeem destination with its token and the whole link, which
 * names its server (T-292): at the host's root and under a mount path alike.
 */
@RunWith(RobolectricTestRunner::class)
class InviteDeepLinkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun open(link: String): Pair<String?, String?> {
        lateinit var navController: NavHostController
        var token: String? = null
        var seenLink: String? = null
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = "home") {
                composable("home") { Text("home") }
                composable(
                    route = Routes.REDEEM_PATTERN,
                    arguments = listOf(
                        navArgument(Routes.TOKEN_ARG) { type = NavType.StringType },
                        navArgument(Routes.LINK_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument(Routes.ACCOUNT_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                    deepLinks = INVITE_LINK_PATTERNS.map { pattern -> navDeepLink { uriPattern = pattern } },
                ) { entry ->
                    token = entry.arguments?.getString(Routes.TOKEN_ARG)
                    seenLink = inviteLinkOf(entry.arguments)
                    Text("redeem")
                }
            }
        }
        composeTestRule.runOnIdle { navController.handleDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("redeem").assertExists()
        return token to seenLink
    }

    @Test
    fun `a link at the host's root`() {
        assertEquals("abc.def" to "https://p23q.org/invite/abc.def", open("https://p23q.org/invite/abc.def"))
    }

    @Test
    fun `a link under a one-segment mount path, as p23q.org/shopping shares them`() {
        assertEquals("abc.def" to "https://p23q.org/shopping/invite/abc.def", open("https://p23q.org/shopping/invite/abc.def"))
    }

    @Test
    fun `a link under a mount path keeps the path in the link`() {
        assertEquals(
            "abc.def" to "https://p23q.org/shopping/stage/invite/abc.def",
            open("https://p23q.org/shopping/stage/invite/abc.def"),
        )
    }

    @Test
    fun `a route built with a link carries it through`() {
        lateinit var navController: NavHostController
        var seenLink: String? = null
        var seenAccount: String? = null
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = "home") {
                composable("home") { Text("home") }
                composable(
                    route = Routes.REDEEM_PATTERN,
                    arguments = listOf(
                        navArgument(Routes.TOKEN_ARG) { type = NavType.StringType },
                        navArgument(Routes.LINK_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument(Routes.ACCOUNT_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    seenLink = inviteLinkOf(entry.arguments)
                    seenAccount = entry.arguments?.getString(Routes.ACCOUNT_ARG)
                    Text("redeem")
                }
            }
        }
        composeTestRule.runOnIdle {
            navController.navigate(Routes.redeem("abc.def", "https://h.example/a b/invite/abc.def?x=1&y=2", "acc-1"))
        }
        composeTestRule.waitForIdle()

        assertEquals("https://h.example/a b/invite/abc.def?x=1&y=2", seenLink)
        assertEquals("acc-1", seenAccount)
    }
}
