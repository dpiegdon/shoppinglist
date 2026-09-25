package org.p23q.shoppinglist.ui.accounts

import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.AppLocale
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.repo.ItemsRepo
import org.p23q.shoppinglist.data.RecordingAuthRepository
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.data.sync.FakeSyncTrigger
import org.p23q.shoppinglist.data.testListsRepo
import org.p23q.shoppinglist.ui.LocalizedContent
import org.p23q.shoppinglist.ui.Routes
import org.robolectric.RobolectricTestRunner

/** One account's screen (T-292): what was the account half of Settings, and removing it. */
@RunWith(RobolectricTestRunner::class)
class AccountScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts
    private val authRepository = RecordingAuthRepository()
    private val viewModels = mutableListOf<AccountViewModel>()

    private var sessionsJson = """{"sessions": []}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/account/sessions") == true -> MockResponse().setResponseCode(200).setBody(sessionsJson)
                request.method == "PATCH" ->
                    MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "XY"}""")
                request.path?.endsWith("/settings") == true ->
                    MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        accounts = TestAccounts(db)
        runBlocking { accounts.add(server.url("/").toString(), email = "milk@example.com") }
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) closeWhenIdle(db, ::idleMainLooper, viewModels, registry = if (::accounts.isInitialized) accounts.registry else null)
        if (::server.isInitialized) server.shutdown()
    }

    private fun newViewModel(id: String = TEST_ACCOUNT_ID) = AccountViewModel(
        SavedStateHandle(mapOf(Routes.ACCOUNT_ID_ARG to id)),
        accounts.registry,
        accounts.sessions,
        authRepository,
        db,
    ).also { viewModels += it }

    /** Waits for the screen's two loads on open, so nothing is still running once the test ends. */
    private fun awaitLoads(viewModel: AccountViewModel) = composeTestRule.waitUntil(timeoutMillis = 5_000) {
        composeTestRule.waitForIdle()
        viewModel.uiState.value.initials != null
    }

    @Test
    fun `shows the account and deleting it on the server asks for the password`() = runBlocking<Unit> {
        val viewModel = newViewModel()
        var gone: AccountGone? = null

        composeTestRule.setContent { AccountScreen(onGone = { gone = it }, onSignIn = {}, viewModel = viewModel) }
        awaitLoads(viewModel)

        composeTestRule.onNodeWithText("milk@example.com").assertExists()
        composeTestRule.onNodeWithText("Server: ${server.url("/")}").assertExists()
        composeTestRule.onNodeWithText("Delete account on server").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Confirm password").assertExists()
        assertEquals(null, gone)
    }

    @Test
    fun `initials load from the account's server and can be edited and saved (T-64)`() = runBlocking<Unit> {
        val viewModel = newViewModel()

        composeTestRule.setContent { AccountScreen(onGone = {}, onSignIn = {}, viewModel = viewModel) }
        awaitLoads(viewModel)

        composeTestRule.onNodeWithText("MI").performScrollTo().performTextReplacement("XY")
        composeTestRule.onAllNodesWithText("Save")[1].performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.initials == "XY"
        }
    }

    @Test
    fun `the current session's device label is translated (T-270)`() = runBlocking<Unit> {
        sessionsJson = """{"sessions": [{"id": "s1", "device_label": "Pixel", "created_at": 0, "last_seen_at": 0, "current": true}]}"""
        val viewModel = newViewModel()

        composeTestRule.setContent {
            LocalizedContent(AppLocale.GERMAN) { AccountScreen(onGone = {}, onSignIn = {}, viewModel = viewModel) }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.sessions.isNotEmpty() && viewModel.uiState.value.initials != null
        }

        composeTestRule.onNodeWithText("Pixel (dieses Gerät)").assertExists()
    }

    @Test
    fun `removing from the phone warns with the unpushed count and the copy hint, then leaves`() = runBlocking<Unit> {
        val list = testListsRepo(db).create(TEST_ACCOUNT_ID, "Groceries")
        ItemsRepo(db, { "this-device" }, FakeSyncTrigger()).createItem(list, "Milk")
        val viewModel = newViewModel()
        var gone: AccountGone? = null

        composeTestRule.setContent { AccountScreen(onGone = { gone = it }, onSignIn = {}, viewModel = viewModel) }
        awaitLoads(viewModel)
        composeTestRule.onNodeWithTag("account-remove").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.isRemoveConfirmOpen
        }

        composeTestRule.onNodeWithText("Remove this account from the phone?").assertExists()
        composeTestRule.onNodeWithText(
            "Its lists and items are deleted from this phone. The account and its lists stay on the server " +
                "and come back when you add the account again.",
        ).assertExists()
        composeTestRule.onNodeWithText("Changes not yet synced, which will be lost: 2").assertExists()
        composeTestRule.onNodeWithText("To keep a list here, copy it to another account first.").assertExists()

        composeTestRule.onNodeWithTag("account-remove-confirm").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            gone != null
        }
        assertEquals(listOf(TEST_ACCOUNT_ID), authRepository.removed)
        // It was the only server account.
        assertEquals(AccountGone.TO_START, gone)
    }

    @Test
    fun `with nothing unpushed the warning names no count`() = runBlocking<Unit> {
        val viewModel = newViewModel()

        composeTestRule.setContent { AccountScreen(onGone = {}, onSignIn = {}, viewModel = viewModel) }
        awaitLoads(viewModel)
        composeTestRule.onNodeWithTag("account-remove").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.isRemoveConfirmOpen
        }

        composeTestRule.onNodeWithText("Changes not yet synced", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("To keep a list here, copy it to another account first.").assertExists()
    }

    @Test
    fun `with the local area on the phone, the copy hint names it too (T-294)`() = runBlocking<Unit> {
        accounts.registry.addLocal()
        val viewModel = newViewModel()

        composeTestRule.setContent { AccountScreen(onGone = {}, onSignIn = {}, viewModel = viewModel) }
        awaitLoads(viewModel)
        composeTestRule.onNodeWithTag("account-remove").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.isRemoveConfirmOpen
        }

        composeTestRule.onNodeWithText("To keep a list here, copy it to another account or to this phone first.").assertExists()
        composeTestRule.onNodeWithText("To keep a list here, copy it to another account first.").assertDoesNotExist()
    }

    @Test
    fun `a signed-out account offers the sign-in`() = runBlocking<Unit> {
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(signedIn = false) }
        val viewModel = newViewModel()
        var signIn = false

        composeTestRule.setContent { AccountScreen(onGone = {}, onSignIn = { signIn = true }, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Signed out. Tap to sign in.").performClick()
        assertTrue(signIn)
    }

    /** T-300: every server action failed at once, and "delete" read its 401 as a wrong password. */
    @Test
    fun `a signed-out account offers only the sign-in and the removal, and asks its server nothing`() = runBlocking<Unit> {
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(signedIn = false) }
        val viewModel = newViewModel()

        composeTestRule.setContent { AccountScreen(onGone = {}, onSignIn = {}, viewModel = viewModel) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("account-sign-in").assertExists()
        composeTestRule.onNodeWithTag("account-remove").assertExists()
        composeTestRule.onNodeWithTag("account-delete").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sessions").assertDoesNotExist()
        composeTestRule.onNodeWithText("Change password").assertDoesNotExist()
        composeTestRule.onNodeWithText("Default currency").assertDoesNotExist()
        assertEquals(0, server.requestCount)
    }

    /** T-300: with several admin accounts, the drawer reaches only the first one's console. */
    @Test
    fun `an admin account's screen opens its own server's console`() = runBlocking<Unit> {
        accounts.registry.update(TEST_ACCOUNT_ID) { it.copy(isAdmin = true) }
        val viewModel = newViewModel()
        var opened = false

        composeTestRule.setContent { AccountScreen(onGone = {}, onSignIn = {}, onOpenAdmin = { opened = true }, viewModel = viewModel) }
        awaitLoads(viewModel)

        composeTestRule.onNodeWithTag("account-admin").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun `a non-admin account's screen offers no console`() = runBlocking<Unit> {
        val viewModel = newViewModel()

        composeTestRule.setContent { AccountScreen(onGone = {}, onSignIn = {}, viewModel = viewModel) }
        awaitLoads(viewModel)

        composeTestRule.onNodeWithTag("account-admin").assertDoesNotExist()
    }

    @Test
    fun `the local area's screen says what it is and counts its lists, and holds its removal while it has any (T-293)`() = runBlocking<Unit> {
        val local = accounts.registry.addLocal()!!
        testListsRepo(db).create(local.id, "Hardware")
        val viewModel = newViewModel(local.id)

        composeTestRule.setContent { AccountScreen(onGone = {}, onSignIn = {}, viewModel = viewModel) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.listCount != null
        }

        composeTestRule.onNodeWithText("On this phone").assertExists()
        composeTestRule.onNodeWithText("Lists: 1").assertExists()
        composeTestRule.onNodeWithText("Only an empty area can be removed. Delete its lists first.").assertExists()
        composeTestRule.onNodeWithTag("account-remove").assertIsNotEnabled()
        // Nothing of a server account's.
        composeTestRule.onNodeWithText("Change password").assertDoesNotExist()
        composeTestRule.onNodeWithTag("account-delete").assertDoesNotExist()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the empty local area is removed at a tap (T-293)`() = runBlocking<Unit> {
        val local = accounts.registry.addLocal()!!
        val viewModel = newViewModel(local.id)
        var gone: AccountGone? = null

        composeTestRule.setContent { AccountScreen(onGone = { gone = it }, onSignIn = {}, viewModel = viewModel) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.listCount == 0
        }
        composeTestRule.onNodeWithText("Lists: 0").assertExists()
        composeTestRule.onNodeWithText("Only an empty area can be removed. Delete its lists first.").assertDoesNotExist()
        composeTestRule.onNodeWithTag("account-remove").assertIsEnabled().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { gone != null }

        assertEquals(listOf(local.id), authRepository.removed)
        assertEquals(AccountGone.TO_ACCOUNTS, gone)
    }
}
