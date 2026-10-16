package org.p23q.shoppinglist.data.notify

import org.p23q.shoppinglist.ui.toJavaLocale
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
import java.util.Locale
import kotlinx.coroutines.flow.first
import org.p23q.shoppinglist.MainActivity
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.account.AccountRegistry
import org.p23q.shoppinglist.core.sync.InviteCheckOutcome
import org.p23q.shoppinglist.core.sync.InviteNotifier
import org.p23q.shoppinglist.core.sync.PendingInvite
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.p23q.shoppinglist.data.accountLine
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.notif_channel_invites
import org.p23q.shoppinglist.resources.notif_invites_count
import org.p23q.shoppinglist.resources.overview_invite_from
import org.p23q.shoppinglist.ui.getString

/**
 * Posts the invites that are new to this phone (T-319) as ONE notification per check: a single
 * invite names its list and who sent it, several are counted and their lists named. A fixed id
 * means a newer check's notification replaces an older unread one; tapping opens the overview,
 * where the invites wait. An invite counts as seen only once it was shown (T-322): posted here, or
 * rendered in the overview's inbox ([org.p23q.shoppinglist.ui.overview.OverviewViewModel]). One a
 * gate stopped stays unseen, so a later check announces it. Gates, in order: app foregrounded, the
 * Invitations switch (Settings), the notification permission. Each check records what decided it, for Settings → Diagnostics.
 */
class InviteNotificationPoster(
    private val context: Context,
    private val prefs: NotificationPrefsStore,
    private val foregroundState: AppForegroundState,
    private val localePreferences: LocalePreferenceStore,
    private val registry: AccountRegistry,
) : InviteNotifier {

    // canPost() checks POST_NOTIFICATIONS before notify(); as in CollaboratorChangeNotificationPoster,
    // lint cannot see the check across the method boundary.
    @SuppressLint("MissingPermission")
    override suspend fun notifyPendingInvites(invites: List<PendingInvite>) {
        val now = System.currentTimeMillis()
        val unseenIds = prefs.unseenInvites(invites.map { it.id }, now)
        val fresh = invites.filter { it.id in unseenIds }.distinctBy { it.id }
        val stoppedBy = when {
            fresh.isEmpty() -> InviteCheckOutcome.NOTHING_NEW
            foregroundState.isForeground -> InviteCheckOutcome.FOREGROUND
            !prefs.inviteNotificationsEnabled.first() -> InviteCheckOutcome.INVITES_OFF
            !canPost() -> InviteCheckOutcome.NO_PERMISSION
            else -> null
        }
        if (stoppedBy != null) {
            // Nothing shown, so nothing marked; the expired seen ids are still pruned.
            prefs.markInvitesSeen(emptyMap(), now)
            return record(now, fresh.size, stoppedBy)
        }

        // In the app's chosen language (T-111): this runs outside composition.
        val locale = localePreferences.effective.first().toJavaLocale()
        ensureChannel(locale)
        val single = fresh.singleOrNull()
        val title = single?.listName ?: getString(locale, Res.string.notif_invites_count, fresh.size)
        val text = if (single != null) {
            getString(locale, Res.string.overview_invite_from, single.invitedByInitials)
        } else {
            fresh.joinToString(", ") { it.listName }
        }
        // Which account the invites were sent to, only with several accounts on the phone (T-292).
        val accounts = registry.load()
        val accountLabel = if (accounts.size > 1) {
            fresh.map { it.accountId }.distinct()
                .mapNotNull { id -> accounts.firstOrNull { it.id == id }?.let(::accountLine) }
                .joinToString(", ")
                .ifEmpty { null }
        } else {
            null
        }
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_OVERVIEW, true)
        }
        // Its own request code, so this PendingIntent does not overwrite the change notification's.
        val pending = PendingIntent.getActivity(
            context, REQUEST_CODE, tapIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(accountLabel)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        prefs.markInvitesSeen(fresh.associate { it.id to it.expiresAt }, now)
        record(now, fresh.size, InviteCheckOutcome.POSTED)
    }

    private suspend fun record(at: Long, newInvites: Int, outcome: InviteCheckOutcome) {
        prefs.recordInviteCheck(InviteCheck(at, newInvites, outcome))
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private suspend fun ensureChannel(locale: Locale) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(locale, Res.string.notif_channel_invites), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    internal companion object {
        const val CHANNEL_ID = "invites"
        const val NOTIFICATION_ID = 1002
        const val REQUEST_CODE = 1
    }
}
