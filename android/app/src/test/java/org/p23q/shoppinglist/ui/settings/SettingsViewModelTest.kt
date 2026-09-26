package org.p23q.shoppinglist.ui.settings

import org.p23q.shoppinglist.data.runCurrentOn
import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.crash.CrashLogWriter
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.UiText
import org.p23q.shoppinglist.ui.accounts.accountRow
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The phone's own settings; an account's live on its Account screen (AccountViewModelTest). */
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var themePreferenceStore: ThemePreferenceStore
    private lateinit var crashLogWriter: CrashLogWriter
    private lateinit var notificationPrefs: NotificationPrefsStore
    private lateinit var db: AppDb
    private lateinit var registry: AccountRegistry
    private val viewModels = mutableListOf<SettingsViewModel>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        registry = AccountRegistry(db)
        runBlocking { registry.add(accountRow("prod")) }
        val themeFile = File.createTempFile("settings_vm_theme", ".preferences_pb")
        themeFile.deleteOnExit()
        themePreferenceStore = ThemePreferenceStore(PreferenceDataStoreFactory.create { themeFile })

        val crashLogFile = File.createTempFile("settings_vm_crash_log", ".txt")
        crashLogFile.deleteOnExit()
        crashLogWriter = CrashLogWriter(crashLogFile)

        val notifPrefsFile = File.createTempFile("settings_vm_notif_prefs", ".preferences_pb")
        notifPrefsFile.deleteOnExit()
        notificationPrefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { notifPrefsFile })
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) {
            closeWhenIdle(db, runCurrentOn(mainDispatcherRule.dispatcher), viewModels, registry = if (::registry.isInitialized) registry else null)
        }
    }

    private fun newViewModel(): SettingsViewModel =
        SettingsViewModel(themePreferenceStore, crashLogWriter, notificationPrefs, registry).also { viewModels += it }

    @Test
    fun `a phone with only the local area has no server account, and gains one when it is added (T-302)`() = runTest(mainDispatcherRule.dispatcher) {
        registry.remove("prod")
        registry.addLocal()
        val viewModel = newViewModel()
        assertFalse(viewModel.uiState.value.hasServerAccount)

        registry.add(accountRow("stage", serverUrl = "https://lists.example.test/stage/"))

        assertTrue(viewModel.uiState.first { it.hasServerAccount }.hasServerAccount)
    }

    @Test
    fun `a phone with a server account has one (T-302)`() = runTest(mainDispatcherRule.dispatcher) {
        assertTrue(newViewModel().uiState.value.hasServerAccount)
    }

    @Test
    fun `setTheme persists the preference and it is reflected in state`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        viewModel.setTheme(ThemePreference.DARK).join()

        assertEquals(ThemePreference.DARK, viewModel.uiState.first { it.theme == ThemePreference.DARK }.theme)
        assertEquals(ThemePreference.DARK, themePreferenceStore.theme.first())
    }

    @Test
    fun `notification toggle state loads from prefs and updates live (T-65)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        assertTrue(viewModel.uiState.first { it.notificationsEnabled }.notificationsEnabled)

        viewModel.setNotificationsEnabled(false).join()

        assertFalse(viewModel.uiState.first { !it.notificationsEnabled }.notificationsEnabled)
        assertFalse(notificationPrefs.notificationsEnabled.first())
    }

    @Test
    fun `the invitations switch is on by default, loads from prefs and leaves the collaborator switch alone (T-319)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        assertTrue(viewModel.uiState.first { it.inviteNotificationsEnabled }.inviteNotificationsEnabled)

        viewModel.setInviteNotificationsEnabled(false).join()

        assertFalse(viewModel.uiState.first { !it.inviteNotificationsEnabled }.inviteNotificationsEnabled)
        assertFalse(notificationPrefs.inviteNotificationsEnabled.first())
        assertTrue(notificationPrefs.notificationsEnabled.first())
        assertTrue(viewModel.uiState.value.notificationsEnabled)
    }

    @Test
    fun `shareLogs with no crash log yet surfaces a message instead of a path (T-50)`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        viewModel.shareLogs()

        assertNull(viewModel.uiState.value.crashLogPath)
        assertEquals(UiText.res(R.string.settings_msg_no_crash_logs), viewModel.uiState.value.infoMessage)
    }

    @Test
    fun `shareLogs with an existing log exposes its path, and consumeCrashLogShare clears it (T-50)`() = runTest(mainDispatcherRule.dispatcher) {
        crashLogWriter.append("main", RuntimeException("boom"))
        val viewModel = newViewModel()

        viewModel.shareLogs()

        assertEquals(crashLogWriter.logFile.absolutePath, viewModel.uiState.value.crashLogPath)

        viewModel.consumeCrashLogShare()

        assertNull(viewModel.uiState.value.crashLogPath)
    }
}
