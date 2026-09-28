package org.p23q.shoppinglist.data.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.p23q.shoppinglist.core.sync.InviteChecker
import org.p23q.shoppinglist.data.AppForegroundState
import javax.inject.Inject

/**
 * The invite check [TuppuSyncWorker] runs after each sync (T-319). Skipped while the app is in
 * the foreground (T-327): the overview fetches invites itself then, and a notification would not
 * be posted anyway, so the request would be spent only on recording "app in foreground".
 */
class BackgroundInviteCheck @Inject constructor(
    private val inviteChecker: InviteChecker,
    private val foreground: AppForegroundState,
) {
    /** Returns whether the check ran. A failure is logged: the next run checks again. */
    suspend fun run(): Boolean {
        if (foreground.isForeground) return false
        try {
            inviteChecker.check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The sync's own result decides the retry; a failed invite check waits for the next run.
            Log.w(TAG, "Invite check failed", e)
        }
        return true
    }

    private companion object {
        const val TAG = "BackgroundInviteCheck"
    }
}
