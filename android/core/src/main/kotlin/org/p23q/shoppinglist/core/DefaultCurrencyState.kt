package org.p23q.shoppinglist.core

import kotlinx.coroutines.flow.Flow
import org.p23q.shoppinglist.core.account.CurrentAccount
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The current account's default currency (T-55), live: a currency change made in Settings reaches
 * an already-open list screen. Derived from the account itself ([CurrentAccount.defaultCurrencyChanges],
 * the account's row in the registry), so a writer just stores it on the account, and a login, a
 * removed account or a different current account shows up here with no one to forget to tell.
 */
@Singleton
class DefaultCurrencyState @Inject constructor(private val currentAccount: CurrentAccount) {
    /** The currency now. */
    val value: String? get() = currentAccount.defaultCurrency

    /** The currency now and at every change; for readers that need live updates (ListViewModel). */
    val currency: Flow<String?> get() = currentAccount.defaultCurrencyChanges
}
