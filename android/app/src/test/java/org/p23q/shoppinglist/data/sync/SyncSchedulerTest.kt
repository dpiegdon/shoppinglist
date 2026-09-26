package org.p23q.shoppinglist.data.sync

import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingPeriodicWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the real SyncScheduler's WorkRequest configs. The unit suite otherwise only ever exercises
 * FakeSyncTrigger, so a request that WorkManager rejects at build() time — an expedited job with an
 * initial delay ("Expedited jobs cannot be delayed") — crashed on every edit and no test caught it.
 * Building each request is where that validation runs; these assert it doesn't throw.
 */
@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {

    private val scheduler = SyncScheduler(ApplicationProvider.getApplicationContext())

    @Test
    fun `after-edit request builds without the expedited-plus-delay crash`() {
        assertNotNull(scheduler.afterEditRequest())
    }

    @Test
    fun `periodic request builds`() {
        assertNotNull(scheduler.periodicRequest())
    }

    @Test
    fun `immediate request builds`() {
        assertNotNull(scheduler.immediateRequest())
    }

    @Test
    fun `every request names TuppuSyncWorker`() {
        val name = TuppuSyncWorker::class.java.name
        assertEquals("org.p23q.shoppinglist.data.sync.TuppuSyncWorker", name)
        assertEquals(name, scheduler.periodicRequest().workSpec.workerClassName)
        assertEquals(name, scheduler.afterEditRequest().workSpec.workerClassName)
        assertEquals(name, scheduler.immediateRequest().workSpec.workerClassName)
    }

    /** The half of SyncSchedulerWorkManagerTest that runs on every host; that one is skipped on aarch64. */
    @Test
    fun `the periodic request replaces a stored one instead of keeping it`() {
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, SyncScheduler.PERIODIC_POLICY)
    }
}
