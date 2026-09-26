package org.p23q.shoppinglist.core.sync

/**
 * One list's worth of collaborator-authored changes pulled in a single sync pass (T-65).
 * [accountId] is the local id of the account whose sync pulled them.
 */
data class CollaboratorChange(
    val accountId: String,
    val listId: String,
    val listName: String,
    val changedItemCount: Int,
)

/**
 * How a collaborator-change check ended (T-318), shown in Settings → Diagnostics so a silent phone
 * can be explained. [FIRST_SYNC] and [NOTHING_FOREIGN] are the engine's; the rest are the
 * notifier's gates, in the order it checks them, or [POSTED].
 */
enum class ChangeCheckOutcome { FIRST_SYNC, NOTHING_FOREIGN, FOREGROUND, NOTIFICATIONS_OFF, LIST_MUTED, NO_PERMISSION, POSTED }

/**
 * Seam between [SyncEngine]'s detection and the Android notification machinery, so the engine
 * (and its JVM tests) never touch NotificationManager. The engine reports RAW detections —
 * preference filtering (global toggle, per-list mutes, foreground) is the implementation's job,
 * and so is recording which gate stopped it.
 */
fun interface CollaboratorChangeNotifier {
    suspend fun notifyCollaboratorChanges(changes: List<CollaboratorChange>)

    /** A check the engine ended before there was anything to notify about (T-318). */
    suspend fun recordCheck(outcome: ChangeCheckOutcome, foreignItems: Int) {}
}
