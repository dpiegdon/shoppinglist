package org.p23q.shoppinglist.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainDispatcherRule
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.crash.CrashLogWriter
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.UiText
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

    @Before
    fun setUp() {
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

    private fun newViewModel(): SettingsViewModel = SettingsViewModel(themePreferenceStore, crashLogWriter, notificationPrefs)

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
