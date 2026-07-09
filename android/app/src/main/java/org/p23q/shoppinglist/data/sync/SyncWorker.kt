package org.p23q.shoppinglist.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (syncEngine.syncNow()) {
        is SyncResult.Success -> Result.success()
        // Unauthorized needs the user to re-login, not a retry; LoginViewModel/UI surfaces that
        // the next time a screen tries to use the API and gets the same UnauthorizedException.
        is SyncResult.Unauthorized -> Result.failure()
        is SyncResult.Failed -> Result.retry()
    }
}
