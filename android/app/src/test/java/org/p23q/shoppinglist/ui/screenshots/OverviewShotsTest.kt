package org.p23q.shoppinglist.ui.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.overview.OverviewScreen
import org.p23q.shoppinglist.ui.overview.OverviewViewModel
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The overview with one account, and with several: sections, banners, invites (T-325). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class OverviewShotsTest(theme: ShotTheme) : ScreenshotTest(theme) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = ShotTheme.both()

        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }

    private suspend fun homeLists() {
        accounts.add("https://lists.example.org/", id = "home", accountId = "acct-sam", email = "sam@example.org")
        accounts.syncStatus.account("home").succeeded(System.currentTimeMillis() - 150_000, 0, 0)
        val groceries = listsRepo.create("home", "Groceries")
        for (name in listOf("Milk", "Bread", "Apples")) itemsRepo.createItem(groceries, name)
        itemsRepo.createItem(groceries, "Coffee", status = Status.CHECKED)
        setMembers(groceries, Triple("acct-sam", "sam@example.org", "SA"), Triple("acct-alex", "alex@example.org", "AL"))
        val chores = listsRepo.create("home", "Chores", ListKind.CHECKLIST)
        for (name in listOf("Water the plants", "Take out the bins")) itemsRepo.createItem(chores, name)
        val trip = listsRepo.create("home", "Trip to Lisbon", ListKind.EXPENSES, currency = "EUR")
        setMembers(trip, Triple("acct-sam", "sam@example.org", "SA"), Triple("acct-alex", "alex@example.org", "AL"))
    }

    private fun show() {
        val viewModel = OverviewViewModel(
            listsRepo,
            itemsRepo,
            accounts.registry,
            accounts.sessions,
            accounts.secrets,
            syncer,
            accounts.syncStatus,
            prefs("overview") { NotificationPrefsStore(it) },
        ).tracked()
        setScreen(
            str(R.string.nav_overview),
            actions = {
                Image(
                    painter = painterResource(R.drawable.ic_brand_logo),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp).size(28.dp),
                )
            },
        ) { OverviewScreen(onOpenList = {}, viewModel = viewModel) }
        awaitUntil { viewModel.uiState.value.lists.isNotEmpty() && viewModel.uiState.value.accounts.isNotEmpty() }
    }

    @Test
    fun oneAccount() = runBlocking<Unit> {
        serverAnswer = { _, path -> if (path == "invites/pending") """{"invites": []}""" else null }
        homeLists()

        show()
        capture("overview-one-account")
    }

    @Test
    fun severalAccounts() = runBlocking<Unit> {
        // Half a day past three days, so the card reads "3 d" however long the run takes.
        val expires = System.currentTimeMillis() + 3 * DAY_MS + DAY_MS / 2
        serverAnswer = { _, path ->
            if (path == "invites/pending") {
                """
                {"invites": [{"id": "inv-1", "list_id": "srv-camping", "list_name": "Camping weekend",
                  "list_kind": "shopping", "invited_by_initials": "KI", "expires_at": $expires, "token": "t-1"}]}
                """.trimIndent()
            } else {
                null
            }
        }
        homeLists()
        accounts.registry.update("home") { it.copy(serverMessage = "Maintenance on Sunday from 9:00") }
        accounts.add("https://work.example.com/tuppu/", id = "work", token = null, accountId = "acct-work", email = "sam@work.example.com")
        listsRepo.create("work", "Office supplies").also { itemsRepo.createItem(it, "Printer paper") }
        val local = checkNotNull(accounts.registry.addLocal())
        listsRepo.create(local.id, "Packing", ListKind.CHECKLIST).also { itemsRepo.createItem(it, "Passport") }

        show()
        awaitUntil { viewModel().uiState.value.invites.isNotEmpty() }
        capture("overview-several-accounts")
    }

    private fun viewModel() = viewModels.filterIsInstance<OverviewViewModel>().single()
}
