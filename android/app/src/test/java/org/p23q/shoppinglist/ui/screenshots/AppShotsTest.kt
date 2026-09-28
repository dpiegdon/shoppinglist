package org.p23q.shoppinglist.ui.screenshots

import android.content.Context
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.crash.CrashLogWriter
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.about.AboutScreen
import org.p23q.shoppinglist.ui.admin.AdminScreen
import org.p23q.shoppinglist.ui.admin.AdminViewModel
import org.p23q.shoppinglist.ui.settings.SettingsScreen
import org.p23q.shoppinglist.ui.settings.SettingsViewModel
import org.p23q.shoppinglist.ui.update.UpdateStatus
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Settings, the admin console and About, light and dark (T-325). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AppShotsTest(theme: ShotTheme) : ScreenshotTest(theme) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = ShotTheme.both()
    }

    @Test
    fun settings() = runBlocking<Unit> {
        accounts.add("https://lists.example.org/", id = "home", email = "sam@example.org")
        val viewModel = SettingsViewModel(
            prefs("theme") { ThemePreferenceStore(it) },
            CrashLogWriter(File.createTempFile("shot_crash", ".txt").apply { deleteOnExit() }),
            prefs("notif") { NotificationPrefsStore(it) },
            accounts.registry,
        ).tracked()

        setScreen(str(R.string.nav_settings)) { SettingsScreen(viewModel = viewModel) }
        capture("settings")
    }

    @Test
    fun admin() = runBlocking<Unit> {
        serverAnswer = { method, path ->
            when {
                method == "GET" && path == "admin/server-settings" ->
                    """{"allow_registration": true, "message": "Maintenance on Sunday from 9:00"}"""
                method == "GET" && path == "admin/users" -> """
                    {"users": [
                      {"id": "u-1", "email": "sam@example.org", "created_at": 1, "session_count": 2, "is_admin": true},
                      {"id": "u-2", "email": "alex@example.org", "created_at": 2, "session_count": 1, "is_admin": false},
                      {"id": "u-3", "email": "kim@example.org", "created_at": 3, "session_count": 0, "is_admin": false}
                    ]}
                """.trimIndent()
                else -> null
            }
        }
        val api = server.apiFactory(json).create("https://lists.example.org/", false, emptyList())
        val viewModel = AdminViewModel({ api }, serverAccountId = "u-1", server = "lists.example.org").tracked()

        setScreen(str(R.string.nav_server_admin), subtitle = viewModel.server) { AdminScreen(viewModel = viewModel) }
        awaitUntil { viewModel.uiState.value.allowRegistration != null }
        composeTestRule.onNodeWithText(str(R.string.admin_show_users)).performClick()
        awaitUntil { viewModel.uiState.value.users != null }
        capture("admin")
    }

    @Test
    fun about() {
        // A fixed version, so a release's bump does not change the picture.
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).versionName = "1.2.3"

        setScreen(str(R.string.nav_about)) { AboutScreen(updateStatus = UpdateStatus.UpToDate("1.2.3")) }
        capture("about")
    }
}
