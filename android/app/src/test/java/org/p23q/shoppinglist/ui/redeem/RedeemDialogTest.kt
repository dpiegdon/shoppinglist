package org.p23q.shoppinglist.ui.redeem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.data.TestAccounts
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RedeemDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun `entering a token and joining redeems it and reports the list id`() = runBlocking {
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
            RedeemDialog(onRedeemed = { redeemedListId = it }, onDismiss = {}, viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Invite code or link").performTextInput("abc.def")
        composeTestRule.onNodeWithText("Join").performClick()
        // redeem() makes two real sequential HTTP round trips (redeemInvite, then syncNow's own
        // sync call) via MockWebServer - a single waitForIdle() doesn't reliably span both, so
        // alternate idling Compose with real wall-clock waits for the background IO to catch up.
        var attempts = 0
        while (redeemedListId == null && attempts < 50) {
            composeTestRule.waitForIdle()
            Thread.sleep(100)
            attempts++
        }

        val localId = db.listDao().getByServerId(org.p23q.shoppinglist.data.TEST_ACCOUNT_ID, "list-42")?.localId
        db.close()
        assertNotNull(localId)
        assertEquals(localId, redeemedListId)
    }
}
