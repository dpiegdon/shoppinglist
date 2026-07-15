package org.p23q.shoppinglist.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import org.p23q.shoppinglist.MainActivity
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.sync.CollaboratorChange
import org.p23q.shoppinglist.data.sync.CollaboratorChangeNotifier
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CollaboratorChangeNotifierModule {
    @Binds
    abstract fun bindNotifier(poster: CollaboratorChangeNotificationPoster): CollaboratorChangeNotifier
}

/**
 * Turns SyncEngine's raw collaborator-change detections into at most ONE notification per sync
 * pass (T-65). Gates, in order: app foregrounded (change already visible on screen — stay
 * silent), global toggle (Settings), per-list mutes (list properties), notification permission
 * (API 33+ runtime; also covers the user disabling notifications in system settings). A fixed
 * notification id means a newer sync's notification replaces a stale unread one.
 */
@Singleton
class CollaboratorChangeNotificationPoster @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: NotificationPrefsStore,
    private val foregroundState: AppForegroundState,
) : CollaboratorChangeNotifier {

    override suspend fun notifyCollaboratorChanges(changes: List<CollaboratorChange>) {
        if (changes.isEmpty() || foregroundState.isForeground) return
        if (!prefs.notificationsEnabled.first()) return
        val muted = prefs.mutedListIds.first()
        val audible = changes.filter { it.listId !in muted }
        if (audible.isEmpty()) return
        if (!canPost()) return

        ensureChannel()
        val totalItems = audible.sumOf { it.changedItemCount }
        val singleList = audible.singleOrNull()
        val title = singleList?.listName ?: "Shopping lists"
        val text = if (singleList != null) {
            itemsChangedText(singleList.changedItemCount)
        } else {
            "${itemsChangedText(totalItems)} across ${audible.size} lists"
        }
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            singleList?.let { putExtra(MainActivity.EXTRA_OPEN_LIST_ID, it.listId) }
        }
        val pending = PendingIntent.getActivity(
            context, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Explicit permission check on 33+ (satisfies lint's MissingPermission); system-toggle check below it. */
    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Collaborator changes", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun itemsChangedText(count: Int): String =
        if (count == 1) "1 item changed" else "$count items changed"

    private companion object {
        const val CHANNEL_ID = "collaborator_changes"
        const val NOTIFICATION_ID = 1001
    }
}
