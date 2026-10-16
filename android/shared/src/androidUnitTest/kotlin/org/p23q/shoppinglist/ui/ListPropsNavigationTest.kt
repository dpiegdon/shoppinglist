package org.p23q.shoppinglist.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Where List properties leads once its list is copied or gone (T-302), whatever the app started on. */
@RunWith(RobolectricTestRunner::class)
class ListPropsNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun host(start: String): NavHostController {
        lateinit var navController: NavHostController
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = start) {
                composable(Routes.OVERVIEW) { Text("overview") }
                composable(Routes.LIST_PATTERN) { entry -> Text("list ${entry.arguments?.getString(Routes.LIST_ID_ARG)}") }
                composable(Routes.LIST_PROPS_PATTERN) { entry -> Text("props ${entry.arguments?.getString(Routes.LIST_ID_ARG)}") }
            }
        }
        return navController
    }

    private fun NavHostController.routes(): List<String?> {
        val routes = mutableListOf<String?>()
        var ok = true
        while (ok) {
            val entry = currentBackStackEntry ?: break
            routes.add(0, entry.destination.route?.replace("{listId}", entry.arguments?.getString(Routes.LIST_ID_ARG) ?: ""))
            ok = popBackStack()
        }
        return routes
    }

    /** A cold start on the last opened list: no overview on the stack. */
    private fun coldStartOnSourceProps(): NavHostController {
        val nav = host(start = Routes.list("src"))
        composeTestRule.runOnIdle { nav.navigate(Routes.listProps("src")) }
        return nav
    }

    @Test
    fun `after a copy, Back from the copy goes to the overview even on a cold start on the source list`() {
        val nav = coldStartOnSourceProps()

        composeTestRule.runOnIdle { nav.afterListDuplicated("copy") }

        composeTestRule.onNodeWithText("list copy").assertExists()
        composeTestRule.runOnIdle { assertEquals(listOf(Routes.OVERVIEW, Routes.list("copy")), nav.routes()) }
    }

    @Test
    fun `after a copy from the overview, Back from the copy goes to the overview`() {
        val nav = host(start = Routes.OVERVIEW)
        composeTestRule.runOnIdle {
            nav.navigate(Routes.list("src"))
            nav.navigate(Routes.listProps("src"))
        }

        composeTestRule.runOnIdle { nav.afterListDuplicated("copy") }

        composeTestRule.runOnIdle { assertEquals(listOf(Routes.OVERVIEW, Routes.list("copy")), nav.routes()) }
    }

    @Test
    fun `after the list is gone, the overview has nothing behind it even on a cold start on that list`() {
        val nav = coldStartOnSourceProps()

        composeTestRule.runOnIdle { nav.afterListLeft() }

        composeTestRule.onNodeWithText("overview").assertExists()
        composeTestRule.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }
}
