package org.p23q.shoppinglist.data.notify

import org.p23q.shoppinglist.core.sync.CollaboratorChange
import org.p23q.shoppinglist.core.sync.CollaboratorChangeNotifier
import org.p23q.shoppinglist.core.sync.InviteNotifier
import org.p23q.shoppinglist.core.sync.PendingInvite

/*
 * Notifications on iOS are step 4 of the port (T-354): they need background syncs and Apple push,
 * which the server does not do yet. Until then the sync engine's findings are dropped here, and
 * the app only syncs while it is open, where the screens show the changes themselves.
 */

class SilentCollaboratorChangeNotifier : CollaboratorChangeNotifier {
    override suspend fun notifyCollaboratorChanges(changes: List<CollaboratorChange>) = Unit
}

class SilentInviteNotifier : InviteNotifier {
    override suspend fun notifyPendingInvites(invites: List<PendingInvite>) = Unit
}
