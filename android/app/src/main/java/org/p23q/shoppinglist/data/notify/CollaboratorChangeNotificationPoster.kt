package org.p23q.shoppinglist.data.notify

import android.Manifest
import android.annotation.SuppressLint
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
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.p23q.shoppinglist.data.sync.CollaboratorChange
import org.p23q.shoppinglist.data.sync.CollaboratorChangeNotifier
import org.p23q.shoppinglist.ui.localizedContext
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
    private val localePreferences: LocalePreferenceStore,
) : CollaboratorChangeNotifier {

    // canPost() already checks POST_NOTIFICATIONS before any notify(); lint's flow analysis can't
    // see the check across that method boundary, so the guarded notify() below is a false positive.
    @SuppressLint("MissingPermission")
    override suspend fun notifyCollaboratorChanges(changes: List<CollaboratorChange>) {
        if (changes.isEmpty() || foregroundState.isForeground) return
        if (!prefs.notificationsEnabled.first()) return
        val muted = prefs.mutedListIds.first()
        val audible = changes.filter { it.listId !in muted }
        if (audible.isEmpty()) return
        if (!canPost()) return

        val totalItems = audible.sumOf { it.changedItemCount }
        val singleList = audible.singleOrNull()
        // Resolved against a LOCALIZED context, not the raw application one (T-111): this runs
        // outside composition, so LocalizedContent cannot reach it and the app's chosen language
        // would otherwise be ignored here while the rest of the UI honoured it.
        val strings = localizedContext(context, localePreferences.effective.first())
        ensureChannel(strings)
        val title = singleList?.listName ?: strings.getString(R.string.notif_shopping_lists)
        val text = if (singleList != null) {
            itemsChangedText(strings, singleList.changedItemCount)
        } else {
            // Two counts, so two labels (T-123) — "N items changed across M lists" would have
            // needed plural agreement twice over.
            strings.getString(R.string.notif_changed_items_lists, totalItems, audible.size)
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

    /** [strings] is the localized context — the channel name is user-visible in system settings. */
    private fun ensureChannel(strings: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                strings.getString(R.string.notif_channel_collaborator),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    // Label-then-count (T-123), so neither string has to agree with its number. The notification
    // still carries the list name as its title, so the body losing its verb costs little.
    private fun itemsChangedText(context: Context, count: Int): String =
        context.getString(R.string.notif_changed_items, count)

    private companion object {
        const val CHANNEL_ID = "collaborator_changes"
        const val NOTIFICATION_ID = 1001
    }
}
