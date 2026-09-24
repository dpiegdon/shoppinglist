package org.p23q.shoppinglist.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.p23q.shoppinglist.core.account.CurrentAccount
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory mirror of the account's default currency (T-55), so a currency change made in Settings
 * reaches an already-open list screen: every writer (login, Settings) calls [set] alongside
 * storing it on the account, and readers that need live updates (e.g. ListViewModel) collect
 * [currency] instead of reading [CurrentAccount.defaultCurrency] once.
 */
@Singleton
class DefaultCurrencyState @Inject constructor(currentAccount: CurrentAccount) {
    private val _currency = MutableStateFlow(currentAccount.defaultCurrency)
    val currency: StateFlow<String?> = _currency.asStateFlow()

    fun set(value: String?) {
        _currency.value = value
    }
}
