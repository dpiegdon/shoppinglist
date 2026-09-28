package org.p23q.shoppinglist.data.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.db.AppDb
import org.p23q.shoppinglist.core.sync.InviteChecker
import org.p23q.shoppinglist.core.sync.PendingInvite
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.TEST_ACCOUNT_ID
import org.p23q.shoppinglist.data.TestAccounts
import org.robolectric.RobolectricTestRunner

/** T-327: the background invite check is skipped while the app is in the foreground. */
@RunWith(RobolectricTestRunner::class)
class BackgroundInviteCheckTest {

    private lateinit var db: AppDb
    private lateinit var accounts: TestAccounts
    private val server = MockWebServer()
    private val handed = mutableListOf<List<PendingInvite>>()
    private val foreground = AppForegroundState()
    private lateinit var check: BackgroundInviteCheck

    @Before
    fun setUp() = runBlocking {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        accounts = TestAccounts(db)
        accounts.add(server.url("/").toString())
        accounts.syncStatus.account(TEST_ACCOUNT_ID).succeeded(at = 1, pending = 0, blocked = 0)
        val checker = InviteChecker(accounts.registry, accounts.sessions, accounts.syncStatus) { handed += it }
        check = BackgroundInviteCheck(checker, foreground)
    }

    @After
    fun tearDown() {
        server.shutdown()
        if (::accounts.isInitialized) runBlocking { accounts.registry.flush() }
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `in the background the invites are asked for and handed on`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"invites": []}"""))

        assertTrue(check.run())

        assertEquals(1, server.requestCount)
        assertEquals(listOf(emptyList<PendingInvite>()), handed)
    }

    @Test
    fun `in the foreground nothing is asked and nothing recorded`() = runBlocking {
        foreground.isForeground = true

        assertFalse(check.run())

        assertEquals(0, server.requestCount)
        assertTrue(handed.isEmpty())
    }
}
