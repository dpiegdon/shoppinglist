package org.p23q.shoppinglist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.account.LastOpenedListStore
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.TuppuApp
import org.p23q.shoppinglist.ui.coldStartDestination

class MainActivity : ComponentActivity() {

    private val lastOpened: LastOpenedListStore by inject()

    private val accountRegistry: AccountRegistry by inject()

    private val notificationPrefs: NotificationPrefsStore by inject()

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
            // An invite notification's tap opens the overview, where the invites wait (T-319).
            openOverview = intent.getBooleanExtra(EXTRA_OPEN_OVERVIEW, false),
            lastOpenedListId = lastOpened.lastOpenedListId,
        )

        if (accounts.any { it.isServer && it.signedIn }) maybeRequestNotificationPermission()

        // The shared app (:shared): language, theme and navigation.
        setContent { TuppuApp(startDestination = startDestination) }
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

        /** An invite notification's tap (T-319): open the overview, not the last-opened list. */
        const val EXTRA_OPEN_OVERVIEW = "open_overview"
    }
}
