package org.p23q.shoppinglist.data.sync

/** One list's worth of collaborator-authored changes pulled in a single sync pass (T-65). */
data class CollaboratorChange(val listId: String, val listName: String, val changedItemCount: Int)

/**
 * Seam between [SyncEngine]'s detection and the Android notification machinery, so the engine
 * (and its JVM tests) never touch NotificationManager. The engine reports RAW detections —
 * preference filtering (global toggle, per-list mutes, foreground) is the implementation's job.
 */
fun interface CollaboratorChangeNotifier {
    suspend fun notifyCollaboratorChanges(changes: List<CollaboratorChange>)
}
