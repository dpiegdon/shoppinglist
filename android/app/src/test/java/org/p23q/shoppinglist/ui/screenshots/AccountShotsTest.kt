package org.p23q.shoppinglist.ui.screenshots

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.RecordingAuthRepository
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.accounts.AccountScreen
import org.p23q.shoppinglist.ui.accounts.AccountViewModel
import org.p23q.shoppinglist.ui.accounts.AccountsScreen
import org.p23q.shoppinglist.ui.accounts.AccountsViewModel
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Accounts screen and one Account screen, light and dark (T-325). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AccountShotsTest(theme: ShotTheme) : ScreenshotTest(theme) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = ShotTheme.both()

        private const val HOUR_MS = 60 * 60 * 1000L
    }

    private suspend fun home() {
        accounts.add("https://lists.example.org/", id = "home", accountId = "acct-sam", email = "sam@example.org")
    }

    @Test
    fun accounts() = runBlocking<Unit> {
        home()
        // Two and a half minutes ago, so the row reads "2 min" however long the run takes.
        accounts.syncStatus.account("home").succeeded(System.currentTimeMillis() - 150_000, pending = 1, blocked = 0)
        accounts.add("https://work.example.com/tuppu/", id = "work", token = null, accountId = "acct-work", email = "sam@work.example.com")
        accounts.add("https://old.example.net/", id = "old", accountId = "acct-old", email = "sam@old.example.net")
        accounts.registry.update("old") { it.copy(outdated = true) }
        checkNotNull(accounts.registry.addLocal())
        val viewModel = AccountsViewModel(accounts.registry, accounts.syncStatus).tracked()

        setScreen(str(R.string.nav_accounts)) {
            AccountsScreen(onAddAccount = {}, onOpenAccount = {}, onSignIn = {}, viewModel = viewModel)
        }
        capture("accounts")
    }

    // Tall enough for the whole screen, down to the sessions and removing the account.
    @Test
    @Config(qualifiers = "w411dp-h1280dp")
    fun account() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        serverAnswer = { method, path ->
            when {
                method == "GET" && path == "account/sessions" -> """
                    {"sessions": [
                      {"id": "s-1", "device_label": "Pixel 8", "created_at": 1, "last_seen_at": $now, "current": true},
                      {"id": "s-2", "device_label": "Firefox on Linux", "created_at": 2, "last_seen_at": ${now - 3 * HOUR_MS - HOUR_MS / 2}, "current": false},
                      {"id": "s-3", "device_label": null, "created_at": 3, "last_seen_at": ${now - 5 * DAY_MS - DAY_MS / 2}, "current": false}
                    ]}
                """.trimIndent()
                method == "GET" && path == "settings" -> """{"default_currency": "EUR", "initials": "SA"}"""
                else -> null
            }
        }
        home()
        val viewModel = AccountViewModel(
            SavedStateHandle(mapOf(Routes.ACCOUNT_ID_ARG to "home")),
            accounts.registry,
            accounts.sessions,
            RecordingAuthRepository(),
            db,
        ).tracked()

        setScreen(str(R.string.settings_account)) { AccountScreen(onGone = {}, onSignIn = {}, viewModel = viewModel) }
        awaitUntil { viewModel.uiState.value.initials != null && viewModel.uiState.value.sessions.isNotEmpty() }
        capture("account")
    }
}
