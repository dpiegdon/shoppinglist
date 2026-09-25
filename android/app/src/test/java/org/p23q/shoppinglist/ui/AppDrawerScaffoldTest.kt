package org.p23q.shoppinglist.ui

import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.sync.SyncStatus
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.testAccount
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDrawerScaffoldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts
    private val viewModels = mutableListOf<DrawerViewModel>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        accounts = TestAccounts(db)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) closeWhenIdle(db, ::idleMainLooper, viewModels, registry = if (::accounts.isInitialized) accounts.registry else null)
    }

    private fun addAccount(id: String, isAdmin: Boolean = false, signedIn: Boolean = true) = runBlocking {
        accounts.registry.add(
            testAccount(id = id, serverUrl = "https://$id.example.test/", accountId = "acct-$id", signedIn = signedIn)
                .copy(isAdmin = isAdmin),
        )
    }

    private fun setDrawerContent(): () -> NavHostController {
        val drawerViewModel = DrawerViewModel(accounts.registry).also { viewModels += it }
        lateinit var navController: NavHostController
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Routes.OVERVIEW) {
                composable(Routes.OVERVIEW) {
                    AppDrawerScaffold(
                        navController = navController,
                        title = "Overview",
                        drawerViewModel = drawerViewModel,
                        syncStatusViewModel = SyncStatusViewModel(SyncStatus()),
                    ) {
                        Text("Overview content")
                    }
                }
                composable(Routes.SETTINGS) { Text("Settings content") }
                composable(Routes.ACCOUNTS) { Text("Accounts content") }
                composable(Routes.ADMIN_PATTERN) { entry -> Text("Admin of ${entry.arguments?.getString(Routes.ACCOUNT_ID_ARG)}") }
                composable(Routes.ABOUT) { Text("About content") }
                composable(Routes.LOGIN_PATTERN) { Text("Login content") }
            }
        }
        return { navController }
    }

    private fun y(text: String) = composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    @Test
    fun `selecting Settings in the drawer navigates to settings`() {
        addAccount("prod")
        val getNavController = setDrawerContent()

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        // Labelled for the screen it opens (T-170); it said "Account".
        composeTestRule.onNodeWithText("Settings").performClick()

        assertEquals(Routes.SETTINGS, getNavController().currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `Accounts replaces Log out, sits before Settings and opens the Accounts screen (T-292)`() {
        addAccount("prod")
        val getNavController = setDrawerContent()

        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        composeTestRule.onNodeWithText("Log out").assertDoesNotExist()
        assertTrue(y("Join a list") < y("Accounts"))
        assertTrue(y("Accounts") < y("Settings"))
        composeTestRule.onNodeWithText("Accounts").performClick()
        assertEquals(Routes.ACCOUNTS, getNavController().currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `tapping the sync status opens Accounts, where each account shows its own (T-292)`() {
        addAccount("prod")
        val getNavController = setDrawerContent()

        // A fresh SyncStatus has never synced: the dot says so in its description (T-178).
        composeTestRule.onNodeWithContentDescription("Not synced yet").performClick()

        assertEquals(Routes.ACCOUNTS, getNavController().currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `an admin gets a Server admin entry after Settings and before About, for that account's server (T-220)`() {
        addAccount("prod")
        addAccount("boss", isAdmin = true)
        val getNavController = setDrawerContent()

        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        assertTrue(y("Settings") < y("Server admin"))
        assertTrue(y("Server admin") < y("About"))
        composeTestRule.onNodeWithText("Server admin").performClick()
        // With one admin account, straight to its console: no chooser.
        composeTestRule.onNodeWithText("Administer which server?").assertDoesNotExist()
        assertEquals(Routes.ADMIN_PATTERN, getNavController().currentBackStackEntry?.destination?.route)
        composeTestRule.onNodeWithText("Admin of boss").assertExists()
    }

    @Test
    fun `with several admin accounts Server admin asks which, each as email and server (T-307)`() {
        addAccount("boss", isAdmin = true)
        addAccount("prod")
        addAccount("stage", isAdmin = true)
        val getNavController = setDrawerContent()

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Server admin").performClick()

        composeTestRule.onNodeWithText("Administer which server?").assertExists()
        composeTestRule.onNodeWithTag("admin-account-boss").assertTextEquals("me@example.com · boss.example.test")
        composeTestRule.onNodeWithTag("admin-account-prod").assertDoesNotExist()
        composeTestRule.onNodeWithTag("admin-account-stage").performClick()

        assertEquals(Routes.ADMIN_PATTERN, getNavController().currentBackStackEntry?.destination?.route)
        composeTestRule.onNodeWithText("Admin of stage").assertExists()
        composeTestRule.onNodeWithText("Administer which server?").assertDoesNotExist()
    }

    @Test
    fun `no signed-in admin account, no Server admin entry (T-220)`() {
        // An affordance only — the server refuses every admin route whatever the drawer shows.
        addAccount("prod")
        addAccount("boss", isAdmin = true, signedIn = false)
        setDrawerContent()

        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        composeTestRule.onNodeWithText("Settings").assertExists()
        composeTestRule.onNodeWithText("Server admin").assertDoesNotExist()
    }

    @Test
    fun `About is the last entry and opens the About screen (T-224)`() {
        addAccount("prod")
        val getNavController = setDrawerContent()

        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        assertTrue(y("Settings") < y("About"))
        composeTestRule.onNodeWithText("About").performClick()
        assertEquals(Routes.ABOUT, getNavController().currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `the top bar shows the sync status only with a server account, never for the local area alone (T-293)`() = runBlocking<Unit> {
        accounts.registry.addLocal()
        setDrawerContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Not synced yet").assertDoesNotExist()

        addAccount("prod")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Not synced yet").assertExists()
    }
}
