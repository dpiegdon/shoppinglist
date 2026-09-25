package org.p23q.shoppinglist.data

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.db.AccountEntity
import org.junit.Assert.assertFalse
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.db.AppDb
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class CloseTestDbTest {

    @Test
    fun `the database closes only once a cancelled scope's query has given its connection back (T-303)`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val scope = CoroutineScope(Dispatchers.IO)
        val holding = CountDownLatch(1)
        val gaveBack = AtomicBoolean(false)
        scope.launch {
            db.useReaderConnection {
                holding.countDown()
                // Stands in for a native step, which cancelling the scope cannot interrupt.
                Thread.sleep(300)
                gaveBack.set(true)
            }
        }
        assertTrue(holding.await(5, TimeUnit.SECONDS))

        closeWhenIdle(db, pumpMain = {}, jobs = listOf(scope.coroutineContext.job))

        assertTrue("the database was closed under a query still holding its connection", gaveBack.get())
    }

    /**
     * T-306: the helper's registry flush used to runBlocking on the pumped thread, and a pending
     * background write is a Room query that, on a database built over the test dispatcher, resumes
     * only when that dispatcher is pumped — so the close deadlocked whenever a write was pending.
     */
    @Test
    fun `a registry write pending on the test dispatcher does not deadlock the close (T-306)`() {
        val dispatcher = StandardTestDispatcher()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(dispatcher)
            .build()
        val registry = AccountRegistry(db)
        runTest(dispatcher) {
            registry.add(
                AccountEntity(
                    id = "acc", serverUrl = "https://lists.example.com/", accountId = "srv-1",
                    email = "a@example.com", label = "a@example.com", signedIn = true,
                ),
            )
        }
        // A write that is now suspended inside Room, waiting for the test dispatcher.
        registry.updateInBackground("acc") { it.copy(label = "renamed") }

        val closer = Thread { closeWhenIdle(db, runCurrentOn(dispatcher), registry = registry) }
        closer.start()
        closer.join(20_000)

        assertFalse("closeWhenIdle deadlocked on a pending registry write", closer.isAlive)
    }
}
