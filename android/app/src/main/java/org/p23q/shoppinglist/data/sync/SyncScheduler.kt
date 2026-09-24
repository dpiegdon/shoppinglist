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
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.core.sync.SyncTrigger
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.core.sync.Syncer
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncTriggerModule {
    @Binds
    abstract fun bindSyncTrigger(scheduler: SyncScheduler): SyncTrigger

    companion object {
        /** Screens depend on the [Syncer] seam for pull-to-refresh; [SyncEngine] is the impl (T-36).
         *  A thin adapter (not @Binds) so SyncEngine keeps its own default-arg public API unchanged.
         *  A list this device does not hold yet is asked of the account that has just joined it. */
        @Provides
        fun provideSyncer(engine: SyncEngine): Syncer = object : Syncer {
            override suspend fun syncNow(fullLists: List<String>): SyncResult = engine.syncNow(fullLists)

            override suspend fun syncJoined(accountId: String, serverListId: String): SyncResult =
                engine.syncNow(listOf(serverListId), fullListsAccountId = accountId)
        }
    }
}

/** Owns every WorkManager entry point for [SyncWorker] (Notes: periodic, after-edit, foreground). */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : SyncTrigger {

    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Background sync every 15 minutes while the network is up. Idempotent — safe to call on every app start. */
    fun schedulePeriodic() {
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
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

    internal fun periodicRequest() = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(networkConstraint)
        .build()

    /** Debounced (5s), NOT expedited — an expedited request with an initial delay is rejected at build(). */
    internal fun afterEditRequest() = OneTimeWorkRequestBuilder<SyncWorker>()
        .setInitialDelay(5, TimeUnit.SECONDS)
        .setConstraints(networkConstraint)
        .build()

    /** Expedited with NO delay — a valid expedited request (unlike the after-edit one). */
    internal fun immediateRequest() = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(networkConstraint)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    private companion object {
        const val PERIODIC_WORK_NAME = "sync-periodic"
        const val EDIT_WORK_NAME = "sync-after-edit"
        const val FOREGROUND_WORK_NAME = "sync-foreground"
    }
}
