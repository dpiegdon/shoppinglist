package org.p23q.shoppinglist.ui.screenshots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore
import org.p23q.shoppinglist.ui.Routes
import org.p23q.shoppinglist.ui.listprops.ListPropsScreen
import org.p23q.shoppinglist.ui.listprops.ListPropsViewModel
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A shared list's properties: categories, roster and invite; and the "Copy to" picker (T-325). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ListPropsShotsTest(theme: ShotTheme) : ScreenshotTest(theme) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = ShotTheme.both()
    }

    private fun show(): ListPropsViewModel = runBlocking {
        serverAnswer = { _, path -> if (path.endsWith("/members")) rosterJson() else null }
        accounts.add("https://lists.example.org/", id = "home", accountId = "acct-sam", email = "sam@example.org")
        accounts.add("https://work.example.com/tuppu/", id = "work", accountId = "acct-work", email = "sam@work.example.com")
        checkNotNull(accounts.registry.addLocal())
        val list = listsRepo.create("home", "Groceries")
        setMembers(list, *ROSTER.toTypedArray())
        listsRepo.setCategoryOrder(list, listOf("Produce", "Dairy", "Bakery"))
        listsRepo.setNotes(list, "Market is open Saturdays until 14:00")
        itemsRepo.createItem(list, "Apples").also { itemsRepo.setCategory(it, "Produce") }
        itemsRepo.createItem(list, "Milk").also { itemsRepo.setCategory(it, "Dairy") }
        itemsRepo.createItem(list, "Butter", Status.CHECKED).also { itemsRepo.setCategory(it, "Dairy") }
        itemsRepo.createItem(list, "Sourdough loaf").also { itemsRepo.setCategory(it, "Bakery") }
        val viewModel = ListPropsViewModel(
            SavedStateHandle(mapOf(Routes.LIST_ID_ARG to list)),
            listsRepo,
            itemsRepo,
            accounts.listAccounts(listsRepo),
            prefs("listprops") { NotificationPrefsStore(it) },
            syncer,
        ).tracked()

        setScreen(str(R.string.nav_list_properties)) { ListPropsScreen(onLeft = {}, onDuplicated = {}, viewModel = viewModel) }
        awaitUntil { viewModel.uiState.value.members.isNotEmpty() }
        viewModel
    }

    // Tall enough for the whole screen, down to leaving the list.
    @Test
    @Config(qualifiers = "w411dp-h1330dp")
    fun properties() {
        show()
        capture("listprops")
    }

    @Test
    fun copyPicker() {
        val viewModel = show()
        composeTestRule.onNodeWithText(str(R.string.action_duplicate)).performScrollTo().performClick()
        awaitUntil { viewModel.uiState.value.copyTargets.isNotEmpty() }
        captureWithDialog("listprops-copy-to")
    }
}
