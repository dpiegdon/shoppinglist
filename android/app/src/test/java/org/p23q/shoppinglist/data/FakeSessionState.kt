package org.p23q.shoppinglist.data

class FakeSessionState : SessionState {
    override var token: String? = null
    override var accountEmail: String? = null
    override var accountId: String? = null
    override var mirrorAccountId: String? = null
    override var isAdmin: Boolean = false
    override var defaultCurrency: String? = null
    override var lastOpenedListId: String? = null
    override var syncCursor: Long = 0L
    override var ignoredInviteIds: Set<String> = emptySet()

    override fun clear() {
        token = null
        accountEmail = null
        accountId = null
        mirrorAccountId = null
        isAdmin = false
        defaultCurrency = null
        lastOpenedListId = null
        syncCursor = 0L
        ignoredInviteIds = emptySet()
    }
}
