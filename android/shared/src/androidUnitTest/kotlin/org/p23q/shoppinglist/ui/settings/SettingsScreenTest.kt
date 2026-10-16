package org.p23q.shoppinglist.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.sync.ChangeCheckOutcome
import org.p23q.shoppinglist.core.sync.InviteCheckOutcome
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.closeWhenIdle
import org.p23q.shoppinglist.data.crash.CrashLogWriter
import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.notify.ChangeCheck
import org.p23q.shoppinglist.data.notify.InviteCheck
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.InBrandColors
import org.p23q.shoppinglist.ui.accounts.accountRow
import org.p23q.shoppinglist.ui.assertPlainSectionCards
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    private fun newViewModel(
        crashLog: File = File.createTempFile("settings_screen_crash_log", ".txt").apply { deleteOnExit() },
        notificationPrefs: NotificationPrefsStore = NotificationPrefsStore(PreferenceDataStoreFactory.create { prefsFile("settings_screen_notif") }),
    ) =
        SettingsViewModel(
            ThemePreferenceStore(PreferenceDataStoreFactory.create { prefsFile("settings_screen_theme") }),
            CrashLogWriter(crashLog),
            notificationPrefs,
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
    fun `each section sits in its own card, as on the web (T-336)`() = runBlocking<Unit> {
        val viewModel = newViewModel()

        composeTestRule.setContent { InBrandColors { SettingsScreen(viewModel = viewModel) } }
        composeTestRule.waitForIdle()

        composeTestRule.assertPlainSectionCards("Theme", "Notifications", "Diagnostics")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `the theme is one segmented control, one choice of three, not chips (A8, T-343)`() = runBlocking<Unit> {
        val viewModel = newViewModel()

        composeTestRule.setContent { InBrandColors { SettingsScreen(viewModel = viewModel) } }
        composeTestRule.waitForIdle()

        // A segment is a radio button of its row; a FilterChip would be a checkbox.
        for (label in listOf("System", "Light", "Dark")) {
            composeTestRule.onNodeWithText(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        composeTestRule.onNodeWithText("Dark").performClick()
        // The choice goes through the preference store and comes back as state.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onNodeWithText("Dark").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }
        }
        composeTestRule.onNodeWithText("Dark").assertIsSelected()
        composeTestRule.onNodeWithText("System").assertIsNotSelected()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `Share crash logs closes its card, so it spans the card as every terminal action does (A11, T-343)`() = runBlocking<Unit> {
        val viewModel = newViewModel()

        composeTestRule.setContent { InBrandColors { SettingsScreen(viewModel = viewModel) } }
        composeTestRule.waitForIdle()

        // The screen is 411dp; 16dp of screen padding and 16dp of card padding on each side.
        val button = composeTestRule.onNodeWithText("Share crash logs").performScrollTo().getBoundsInRoot()
        assertEquals(347f, (button.right - button.left).value, 1f)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `a phone with a server account shows the collaborator switch and the background sync (T-302)`() = runBlocking<Unit> {
        val viewModel = newViewModel()

        composeTestRule.setContent { SettingsScreen(viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Collaborator changes").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Last background sync", substring = true).performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Last change check: never").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Last invite check: never").performScrollTo().assertExists()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `diagnostics say how the last change check ended (T-318)`() = runBlocking<Unit> {
        val prefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { prefsFile("settings_screen_notif_check") })
        prefs.recordChangeCheck(ChangeCheck(System.currentTimeMillis(), foreignItems = 2, outcome = ChangeCheckOutcome.LIST_MUTED))
        val viewModel = newViewModel(notificationPrefs = prefs)

        composeTestRule.setContent { SettingsScreen(viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Last change check: just now, foreign items pulled: 2, list muted").performScrollTo().assertExists()
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
        composeTestRule.onNodeWithText("Last change check", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Invitations").assertDoesNotExist()
        composeTestRule.onNodeWithText("Last invite check", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Share crash logs").performScrollTo().assertExists()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `the Invitations switch sits under Notifications, is on by default and turns off on its own (T-319)`() = runBlocking<Unit> {
        val prefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { prefsFile("settings_screen_notif_invites") })
        val viewModel = newViewModel(notificationPrefs = prefs)

        composeTestRule.setContent { SettingsScreen(viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Invitations").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Notify when someone invites you to a shared list.").assertExists()
        composeTestRule.onNodeWithTag("invite-notifications-switch").performScrollTo().assertIsOn().performClick()
        // The write lands on DataStore's own thread; the switch follows the stored value.
        composeTestRule.waitUntil(5_000) {
            idleMainLooper()
            runBlocking { !prefs.inviteNotificationsEnabled.first() }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("invite-notifications-switch").assertIsOff()
        assertFalse(prefs.inviteNotificationsEnabled.first())
        assertTrue(prefs.notificationsEnabled.first())
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `diagnostics say how the last invite check ended (T-319)`() = runBlocking<Unit> {
        val prefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { prefsFile("settings_screen_invite_check") })
        prefs.recordInviteCheck(InviteCheck(System.currentTimeMillis(), newInvites = 2, outcome = InviteCheckOutcome.INVITES_OFF))
        val viewModel = newViewModel(notificationPrefs = prefs)

        composeTestRule.setContent { SettingsScreen(viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Last invite check: just now, new invitations: 2, invitations off").performScrollTo().assertExists()
        viewModel.viewModelScope.cancel()
    }
}
