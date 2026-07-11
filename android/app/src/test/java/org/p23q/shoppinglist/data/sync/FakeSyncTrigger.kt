package org.p23q.shoppinglist.data.sync

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
