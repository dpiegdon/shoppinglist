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
}
