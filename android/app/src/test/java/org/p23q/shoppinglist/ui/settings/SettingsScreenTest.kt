package org.p23q.shoppinglist.ui.settings

import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.crash.CrashLogWriter
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.accounts.accountRow
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The phone's own settings. Everything an account owns is on its Account screen (AccountScreenTest). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: AppDb
    private lateinit var registry: AccountRegistry

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        registry = AccountRegistry(db)
        runBlocking { registry.add(accountRow("prod")) }
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) closeWhenIdle(db, ::idleMainLooper, viewModels, registry = if (::registry.isInitialized) registry else null)
    }

    private val viewModels = mutableListOf<SettingsViewModel>()

    private fun prefsFile(name: String) = File.createTempFile(name, ".preferences_pb").apply { deleteOnExit() }

    private fun newViewModel(crashLog: File = File.createTempFile("settings_screen_crash_log", ".txt").apply { deleteOnExit() }) =
        SettingsViewModel(
            ThemePreferenceStore(PreferenceDataStoreFactory.create { prefsFile("settings_screen_theme") }),
            CrashLogWriter(crashLog),
            NotificationPrefsStore(PreferenceDataStoreFactory.create { prefsFile("settings_screen_notif") }),
            registry,
        ).also { viewModels += it }

    @Test
    fun `tapping Share crash logs with no log yet surfaces a message instead of a broken share sheet (T-50)`() = runBlocking<Unit> {
        val viewModel = newViewModel()

        composeTestRule.setContent { SettingsScreen(viewModel = viewModel) }
        composeTestRule.onNodeWithText("Share crash logs").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("No crash logs yet").assertExists()
        assertEquals(null, viewModel.uiState.value.crashLogPath)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `settings is the phone's — theme, notifications, diagnostics, nothing of an account (T-224, T-292)`() = runBlocking<Unit> {
        val viewModel = newViewModel()

        composeTestRule.setContent { SettingsScreen(viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Theme").assertExists()
        composeTestRule.onNodeWithText("Notifications").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Diagnostics").performScrollTo().assertExists()
        // The account's own sections moved to its Account screen.
        composeTestRule.onNodeWithText("Default currency").assertDoesNotExist()
        composeTestRule.onNodeWithText("Displayed initials").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sessions").assertDoesNotExist()
        composeTestRule.onNodeWithText("Danger zone").assertDoesNotExist()
        composeTestRule.onNodeWithText("Server admin").assertDoesNotExist()
        // The whole App-updates block and the version line are on About.
        composeTestRule.onNodeWithText("App updates").assertDoesNotExist()
        composeTestRule.onAllNodesWithText("Version", substring = true).assertCountEquals(0)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `a phone with a server account shows the collaborator switch and the background sync (T-302)`() = runBlocking<Unit> {
        val viewModel = newViewModel()

        composeTestRule.setContent { SettingsScreen(viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Collaborator changes").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Last background sync", substring = true).performScrollTo().assertExists()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `a phone with only the local area has no collaborator switch and no background sync (T-302)`() = runBlocking<Unit> {
        registry.remove("prod")
        registry.addLocal()
        val viewModel = newViewModel()

        composeTestRule.setContent { SettingsScreen(viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Notifications").assertDoesNotExist()
        composeTestRule.onNodeWithText("Collaborator changes").assertDoesNotExist()
        composeTestRule.onNodeWithText("Last background sync", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Share crash logs").performScrollTo().assertExists()
        viewModel.viewModelScope.cancel()
    }
}
