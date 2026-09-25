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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.data.deviceLocale
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.LocalizedContent
import org.p23q.shoppinglist.ui.ShoppingListNavHost
import org.p23q.shoppinglist.ui.coldStartDestination
import org.p23q.shoppinglist.ui.theme.ShoppingListTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themePreferenceStore: ThemePreferenceStore

    @Inject lateinit var localePreferenceStore: LocalePreferenceStore

    @Inject lateinit var lastOpened: LastOpenedListStore

    @Inject lateinit var accountRegistry: AccountRegistry

    @Inject lateinit var notificationPrefs: NotificationPrefsStore

    // Registered up-front (required before STARTED); .launch() is deferred to maybeAsk...() below.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result surfaces via the OS */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Resume on cold start instead of always showing the start screen. The decision is
        // resolved here, before setContent, and passed as the nav start destination, so the
        // accounts are loaded first: one read of a small table (and, once, the schema-9
        // migration), after which every account read below and in the view models is synchronous.
        // An account the server has signed out still opens the app on its lists, and so does the
        // local area alone: only a phone with no account at all starts on the start screen.
        val accounts = runBlocking(Dispatchers.IO) { accountRegistry.load() }
        val startDestination = coldStartDestination(
            accounts = accounts,
            // A collaborator-change notification tap deep-links straight to the affected list (T-65).
            notifiedListId = intent.getStringExtra(EXTRA_OPEN_LIST_ID),
            lastOpenedListId = lastOpened.lastOpenedListId,
        )

        if (accounts.any { it.isServer && it.signedIn }) maybeRequestNotificationPermission()

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
