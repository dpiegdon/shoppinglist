package org.p23q.shoppinglist.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.p23q.shoppinglist.core.sync.InviteChecker
import org.p23q.shoppinglist.core.sync.SyncEngine
import org.p23q.shoppinglist.core.sync.SyncResult
import org.p23q.shoppinglist.data.notify.NotificationPrefsStore

@HiltWorker
class TuppuSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val notificationPrefs: NotificationPrefsStore,
    private val inviteChecker: InviteChecker,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Record that background work actually ran (T-112): surfaced in Settings → Diagnostics and
        // logged, so the user can tell whether the OS is running WorkManager at all (vs. killing it
        // via battery optimization / Doze, the usual reason notifications never fire on-device).
        Log.i(TAG, "TuppuSyncWorker running")
        notificationPrefs.recordBackgroundSync(System.currentTimeMillis())
        val result = syncEngine.syncNow()
        // New invites are noticed here rather than by polling (T-319): one small request per
        // account whose sync just succeeded, whatever the overall result — one account failing
        // must not cost another its invites.
        try {
            inviteChecker.check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The sync's own result decides the retry; a failed invite check waits for the next run.
            Log.w(TAG, "Invite check failed", e)
        }
        return when (result) {
            is SyncResult.Success -> Result.success()
            // Unauthorized needs the user to re-login, not a retry; LoginViewModel/UI surfaces that
            // the next time a screen tries to use the API and gets the same UnauthorizedException.
            is SyncResult.Unauthorized -> Result.failure()
            // Retrying would hammer a server that will refuse every request until this app is
            // updated (T-240); the blocking update screen is what resolves it, not a backoff.
            is SyncResult.UpdateRequired -> Result.failure()
            is SyncResult.Failed -> Result.retry()
        }
    }

    private companion object {
        const val TAG = "TuppuSyncWorker"
    }
}
