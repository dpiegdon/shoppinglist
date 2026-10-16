package org.p23q.shoppinglist.data.sync

import org.p23q.shoppinglist.core.sync.SyncTrigger

class FakeSyncTrigger : SyncTrigger {
    var scheduleCount = 0
        private set
    var immediateCount = 0
        private set

    override fun scheduleAfterEdit() {
        scheduleCount++
    }

    override fun scheduleImmediate() {
        immediateCount++
    }
}
