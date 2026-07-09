package org.p23q.shoppinglist.data

class FakeSessionState : SessionState {
    override var token: String? = null
    override var accountEmail: String? = null
    override var defaultCurrency: String? = null
    override var lastOpenedListId: String? = null
    override var syncCursor: Long = 0L

    override fun clear() {
        token = null
        accountEmail = null
        defaultCurrency = null
        lastOpenedListId = null
        syncCursor = 0L
    }
}
