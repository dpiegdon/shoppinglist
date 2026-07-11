package org.p23q.shoppinglist.data.sync

import androidx.test.core.app.ApplicationProvider
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
}
