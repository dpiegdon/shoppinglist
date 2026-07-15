package org.p23q.shoppinglist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.ThemePreference
import org.p23q.shoppinglist.data.ThemePreferenceStore
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.ShoppingListNavHost
import org.p23q.shoppinglist.ui.authedStartDestination
import org.p23q.shoppinglist.ui.theme.ShoppingListTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themePreferenceStore: ThemePreferenceStore

    @Inject lateinit var session: SessionState

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

        setContent {
            val themePreference by themePreferenceStore.theme.collectAsStateWithLifecycle(initialValue = ThemePreference.SYSTEM)
            val darkTheme = when (themePreference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            ShoppingListTheme(darkTheme = darkTheme) {
                // The base Android theme (themes.xml) is a fixed light theme, not day/night — so
                // without this Surface, dark mode paints the DarkColors palette's light-on-dark text
                // over the window's still-white background ("light gray on white"). Surface is the
                // one composable that actually fills the screen with colorScheme.background.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ShoppingListNavHost(startDestination = startDestination)
                }
            }
        }
    }

    companion object {
        /** A notification tap's target list (T-65); absent = open normally. */
        const val EXTRA_OPEN_LIST_ID = "open_list_id"
    }
}
