package org.p23q.shoppinglist.ui

import org.p23q.shoppinglist.core.db.AccountEntity

/**
 * The order accounts are listed in everywhere (T-292): server accounts in the user's order, then
 * this phone's own local area, which always comes last. The overview, the Accounts screen and the
 * "Copy to" picker all go by it.
 */
internal fun overviewOrder(accounts: List<AccountEntity>): List<AccountEntity> =
    accounts.sortedWith(compareBy<AccountEntity> { if (it.isServer) 0 else 1 }.thenBy { it.sortOrder })
