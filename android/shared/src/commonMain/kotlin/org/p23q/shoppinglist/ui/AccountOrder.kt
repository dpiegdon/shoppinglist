package org.p23q.shoppinglist.ui

import org.p23q.shoppinglist.core.db.AccountEntity

/**
 * The order accounts are listed in everywhere (T-292): the user's order alone, the local area
 * placed like any other account (T-309). The overview, the Accounts screen and the "Copy to"
 * picker all go by it.
 */
internal fun overviewOrder(accounts: List<AccountEntity>): List<AccountEntity> = accounts.sortedBy { it.sortOrder }
