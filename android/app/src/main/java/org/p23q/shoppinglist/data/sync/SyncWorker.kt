package org.p23q.shoppinglist.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val notificationPrefs: NotificationPrefsStore,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Record that background work actually ran (T-112): surfaced in Settings → Diagnostics and
        // logged, so the user can tell whether the OS is running WorkManager at all (vs. killing it
        // via battery optimization / Doze, the usual reason notifications never fire on-device).
        Log.i(TAG, "SyncWorker running")
        notificationPrefs.recordBackgroundSync(System.currentTimeMillis())
        return when (syncEngine.syncNow()) {
            is SyncResult.Success -> Result.success()
            // Unauthorized needs the user to re-login, not a retry; LoginViewModel/UI surfaces that
            // the next time a screen tries to use the API and gets the same UnauthorizedException.
            is SyncResult.Unauthorized -> Result.failure()
            is SyncResult.Failed -> Result.retry()
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
