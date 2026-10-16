package org.p23q.shoppinglist.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.p23q.shoppinglist.core.sync.SyncTrigger
import java.util.concurrent.TimeUnit

/** Owns every WorkManager entry point for [TuppuSyncWorker] (Notes: periodic, after-edit, foreground). */
class SyncScheduler(private val context: Context) : SyncTrigger {

    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Background sync every 15 minutes while the network is up. Idempotent — safe to call on every app start.
     *
     * See [PERIODIC_POLICY] for why the stored request is updated rather than kept.
     */
    fun schedulePeriodic() {
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, PERIODIC_POLICY, periodicRequest())
    }

    override fun scheduleAfterEdit() {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(EDIT_WORK_NAME, ExistingWorkPolicy.REPLACE, afterEditRequest())
    }

    /** Immediate sync, e.g. when the app returns to the foreground, or right after login (Notes). */
    override fun scheduleImmediate() {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FOREGROUND_WORK_NAME, ExistingWorkPolicy.REPLACE, immediateRequest())
    }

    // The request builders are internal so a test can assert they *build* — WorkRequest.Builder.build()
    // validates the config (e.g. rejects expedited + initial delay: "Expedited jobs cannot be delayed"),
    // which crashed every edit and was invisible to the fake-trigger unit tests.

    internal fun periodicRequest() = PeriodicWorkRequestBuilder<TuppuSyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(networkConstraint)
        .build()

    /** Debounced (5s), NOT expedited — an expedited request with an initial delay is rejected at build(). */
    internal fun afterEditRequest() = OneTimeWorkRequestBuilder<TuppuSyncWorker>()
        .setInitialDelay(5, TimeUnit.SECONDS)
        .setConstraints(networkConstraint)
        .build()

    /** Expedited with NO delay — a valid expedited request (unlike the after-edit one). */
    internal fun immediateRequest() = OneTimeWorkRequestBuilder<TuppuSyncWorker>()
        .setConstraints(networkConstraint)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    internal companion object {
        const val PERIODIC_WORK_NAME = "sync-periodic"
        const val EDIT_WORK_NAME = "sync-after-edit"
        const val FOREGROUND_WORK_NAME = "sync-foreground"

        /**
         * UPDATE, not KEEP: WorkManager stores a request by its worker's class name, so a request an
         * older version enqueued, naming a class that no longer exists, would be kept and never run
         * again. UPDATE swaps the stored request for this one while keeping its schedule, so calling
         * it on every app start does not push the next run back.
         */
        val PERIODIC_POLICY = ExistingPeriodicWorkPolicy.UPDATE
    }
}
