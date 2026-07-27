package org.p23q.shoppinglist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.deviceLocale
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.LocalizedContent
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.ShoppingListNavHost
import org.p23q.shoppinglist.ui.authedStartDestination
import org.p23q.shoppinglist.ui.theme.ShoppingListTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themePreferenceStore: ThemePreferenceStore

    @Inject lateinit var localePreferenceStore: LocalePreferenceStore

    @Inject lateinit var session: SessionState

    @Inject lateinit var notificationPrefs: NotificationPrefsStore

    // Registered up-front (required before STARTED); .launch() is deferred to maybeAsk...() below.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result surfaces via the OS */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Resume the session on cold start instead of always dumping the user on a blank Login
        // form. token/lastOpenedListId are synchronous (EncryptedSharedPreferences) reads, so the
        // decision is resolved here, before setContent, and passed as the nav start destination.
        // (A revoked token still surfaces later via the forced-logout path in ShoppingListNavHost.)
        val notifiedListId = intent.getStringExtra(EXTRA_OPEN_LIST_ID)
        val startDestination = when {
            session.token == null -> Routes.LOGIN
            // A collaborator-change notification tap deep-links straight to the affected list (T-65).
            notifiedListId != null -> Routes.list(notifiedListId)
            else -> authedStartDestination(session.lastOpenedListId)
        }

        if (session.token != null) maybeRequestNotificationPermission()

        setContent {
            val themePreference by themePreferenceStore.theme.collectAsStateWithLifecycle(initialValue = ThemePreference.SYSTEM)
            val darkTheme = when (themePreference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            // Seeded with the device language rather than a fixed default, so the very first
            // frame is already in the right language for a user who has never chosen one — an
            // English flash before the stored value arrives would be visible on every cold start.
            val locale by localePreferenceStore.effective.collectAsStateWithLifecycle(
                initialValue = deviceLocale(),
            )
            // Outermost, so the theme and every screen below resolve strings — and layout
            // direction — in the chosen language (T-111/T-126).
            LocalizedContent(locale) {
                ShoppingListTheme(darkTheme = darkTheme) {
                    // The base Android theme (themes.xml) is a fixed light theme, not day/night — so
                    // without this Surface, dark mode paints the DarkColors palette's light-on-dark
                    // text over the window's still-white background ("light gray on white"). Surface
                    // is the one composable that actually fills the screen with
                    // colorScheme.background.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ShoppingListNavHost(startDestination = startDestination)
                    }
                }
            }
        }
    }

    /**
     * Asks for POST_NOTIFICATIONS once, after login (T-72). The in-app notifications toggle defaults
     * on, so its own flip-to-enable request never fires on a fresh install — without this, a new
     * user never gets prompted and collaborator notifications silently never post. Guarded by a
     * persisted flag so we prompt at most once; the user can still (re)enable via Settings.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        lifecycleScope.launch {
            val enabled = notificationPrefs.notificationsEnabled.first()
            val alreadyAsked = notificationPrefs.notificationPermissionRequested.first()
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (enabled && !alreadyAsked && !granted) {
                // Set the flag before launching so a dismissed dialog still counts as "asked".
                notificationPrefs.setNotificationPermissionRequested(true)
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        /** A notification tap's target list (T-65); absent = open normally. */
        const val EXTRA_OPEN_LIST_ID = "open_list_id"
    }
}
