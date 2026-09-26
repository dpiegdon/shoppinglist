package org.p23q.shoppinglist.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.impl.Processor
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.close
import androidx.work.impl.constraints.trackers.Trackers
import androidx.work.impl.model.WorkSpec
import androidx.work.impl.schedulers
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/**
 * [SyncScheduler.schedulePeriodic] against a real WorkManager, holding the periodic request an
 * older version of the app stored. WorkManager keeps a request by its worker's class name, so a
 * request naming a class that has since been renamed would never run again unless it is replaced.
 *
 * androidx.work:work-testing is not a dependency, so this builds the WorkManager by hand: every
 * executor runs its task on the calling thread, no schedulers are attached (nothing is meant to
 * run), and its database is in memory and closed in [tearDown], so nothing of it outlives the test
 * (the leak RobolectricTestApp describes). WorkManager's database needs a SupportSQLiteOpenHelper,
 * so it cannot use BundledSQLiteDriver and runs on Robolectric's SQLite shadows, which have no
 * Linux/aarch64 binaries: there the test is skipped, and SyncSchedulerTest still checks the policy.
 */
@RunWith(RobolectricTestRunner::class)
class SyncSchedulerWorkManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: WorkDatabase
    private lateinit var workManager: WorkManagerImpl

    @Before
    fun setUp() {
        assumeFalse(
            "Robolectric's SQLite shadows do not run on Linux/aarch64",
            System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "aarch64",
        )
        val direct = Executor { it.run() }
        val taskExecutor = object : TaskExecutor {
            private val serial = object : SerialExecutor {
                override fun execute(command: Runnable) = command.run()
                override fun hasPendingTasks() = false
            }

            override fun getMainThreadExecutor() = direct
            override fun getSerialTaskExecutor() = serial
            override fun getTaskCoroutineDispatcher() = Dispatchers.Unconfined
        }
        val configuration = Configuration.Builder().setExecutor(direct).setTaskExecutor(direct).build()
        db = Room.inMemoryDatabaseBuilder(context, WorkDatabase::class.java)
            .setQueryExecutor(direct)
            .allowMainThreadQueries()
            .build()
        workManager = WorkManagerImpl(
            context,
            configuration,
            taskExecutor,
            db,
            Trackers(context, taskExecutor),
            Processor(context, configuration, taskExecutor, db),
            schedulers(),
        )
        WorkManagerImpl.setDelegate(workManager)
    }

    @After
    fun tearDown() {
        WorkManagerImpl.setDelegate(null)
        if (::workManager.isInitialized) workManager.close()
        if (::db.isInitialized) db.close()
    }

    private fun storedPeriodic(): WorkSpec {
        val stored = db.workSpecDao().getWorkSpecIdAndStatesForName(SyncScheduler.PERIODIC_WORK_NAME)
        assertEquals(1, stored.size)
        return db.workSpecDao().getWorkSpec(stored.single().id)!!
    }

    @Test
    fun `a periodic request stored under the old worker class is replaced by one naming TuppuSyncWorker`() {
        val scheduler = SyncScheduler(context)
        // What an upgraded phone holds: the periodic request as enqueued before the rename.
        val old = scheduler.periodicRequest().also { it.workSpec.workerClassName = OLD_WORKER_CLASS }
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SyncScheduler.PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, old)
            .result.get()
        assertEquals(OLD_WORKER_CLASS, storedPeriodic().workerClassName)
        val enqueuedAt = storedPeriodic().lastEnqueueTime

        scheduler.schedulePeriodic()

        val replaced = storedPeriodic()
        assertEquals(TuppuSyncWorker::class.java.name, replaced.workerClassName)
        // Replaced in place: still the one request, on its original schedule.
        assertEquals(old.id.toString(), replaced.id)
        assertEquals(enqueuedAt, replaced.lastEnqueueTime)
    }

    @Test
    fun `scheduling again on every app start keeps a single periodic request`() {
        val scheduler = SyncScheduler(context)
        scheduler.schedulePeriodic()
        val first = storedPeriodic()

        scheduler.schedulePeriodic()

        val second = storedPeriodic()
        assertEquals(first.id, second.id)
        assertEquals(first.lastEnqueueTime, second.lastEnqueueTime)
        assertEquals(TuppuSyncWorker::class.java.name, second.workerClassName)
    }

    private companion object {
        const val OLD_WORKER_CLASS = "org.p23q.shoppinglist.data.sync.SyncWorker"
    }
}
