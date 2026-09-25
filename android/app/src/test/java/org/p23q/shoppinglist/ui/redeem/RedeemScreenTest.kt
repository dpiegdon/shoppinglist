package org.p23q.shoppinglist.ui.redeem

import org.p23q.shoppinglist.data.idleMainLooper
import org.p23q.shoppinglist.data.closeWhenIdle
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.TestAccounts
import org.p23q.shoppinglist.core.db.AppDb
import org.robolectric.RobolectricTestRunner
import org.p23q.shoppinglist.ui.UiText

@RunWith(RobolectricTestRunner::class)
class RedeemScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun `auto-redeems the token from the App Link and reports the list id`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"list_id": "list-42"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(org.p23q.shoppinglist.data.syncResponseWithList("list-42")))

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val accounts = TestAccounts(db)
        accounts.add(server.url("/").toString())
        val viewModel = RedeemViewModel(accounts.registry, accounts.sessions, accounts.syncer(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.testListsRepo(db))
        var redeemedListId: String? = null

        composeTestRule.setContent {
            RedeemScreen(token = "abc.def", onRedeemed = { redeemedListId = it }, onCancel = {}, viewModel = viewModel)
        }
        // See RedeemDialogTest: redeem() makes two real sequential HTTP round trips, so a single
        // waitForIdle() doesn't reliably span both.
        var attempts = 0
        while (redeemedListId == null && attempts < 50) {
            composeTestRule.waitForIdle()
            Thread.sleep(100)
            attempts++
        }

        val localId = db.listDao().getByServerId(org.p23q.shoppinglist.data.TEST_ACCOUNT_ID, "list-42")?.localId
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel), registry = accounts.registry)
        assertNotNull(localId)
        assertEquals(localId, redeemedListId)
    }

    @Test
    fun `a redeem failure shows the error and a way back`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error": "invalid_token", "message": "Bad invite link"}"""),
        )

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val accounts = TestAccounts(db)
        accounts.add(server.url("/").toString())
        val viewModel = RedeemViewModel(accounts.registry, accounts.sessions, accounts.syncer(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.testListsRepo(db))

        composeTestRule.setContent {
            RedeemScreen(token = "bad-token", onRedeemed = {}, onCancel = {}, viewModel = viewModel)
        }
        // redeem() auto-fires on mount and the 404 is a real MockWebServer round trip, which
        // waitForIdle() alone doesn't wait for (T-96) - poll the observed state, re-idling Compose
        // each attempt so a response that lands late still gets picked up.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            viewModel.uiState.value.errorMessage == UiText.res(R.string.api_error_invite_not_found)
        }

        // A garbled link reads as an invite that does not exist, in the app's language (see ErrorText).
        composeTestRule.onNodeWithText("This invite doesn't exist").assertExists()
        composeTestRule.onNodeWithText("Back").assertExists()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel), registry = accounts.registry)
    }

    @Test
    fun `with several accounts the screen names the one it joins with`() = runBlocking {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"list_id": "list-42"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(org.p23q.shoppinglist.data.syncResponseWithList("list-42")))
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val accounts = TestAccounts(db)
        accounts.add(server.url("/").toString())
        accounts.add("https://elsewhere.example.test/", id = "other", accountId = "acct-other", email = "other@example.com")
        val viewModel = RedeemViewModel(accounts.registry, accounts.sessions, accounts.syncer(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.testListsRepo(db))
        var redeemedListId: String? = null

        composeTestRule.setContent {
            RedeemScreen(
                token = "abc.def",
                link = server.url("/invite/abc.def").toString(),
                onRedeemed = { redeemedListId = it },
                onCancel = {},
                viewModel = viewModel,
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.waitForIdle()
            redeemedListId != null
        }

        composeTestRule.onNodeWithText("me@example.com").assertExists()
        composeTestRule.onNodeWithText(server.url("/").toString()).assertExists()
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel), registry = accounts.registry)
    }

    @Test
    fun `two accounts on the invite's server ask which to join with`() = runBlocking {
        server = MockWebServer()
        server.start()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        val accounts = TestAccounts(db)
        accounts.add(server.url("/").toString())
        accounts.add(server.url("/").toString(), id = "second", accountId = "acct-second", email = "second@example.com")
        val viewModel = RedeemViewModel(accounts.registry, accounts.sessions, accounts.syncer(), org.p23q.shoppinglist.data.PendingInviteHolder(), org.p23q.shoppinglist.data.testListsRepo(db))

        composeTestRule.setContent {
            RedeemScreen(token = "abc.def", link = server.url("/invite/abc.def").toString(), onRedeemed = {}, onCancel = {}, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Join with which account?").assertExists()
        composeTestRule.onNodeWithText("me@example.com").assertExists()
        composeTestRule.onNodeWithText("second@example.com").assertExists()
        assertEquals(0, server.requestCount)
        closeWhenIdle(db, ::idleMainLooper, listOf(viewModel), registry = accounts.registry)
    }
}
