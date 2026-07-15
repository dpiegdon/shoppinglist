package org.p23q.shoppinglist.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory mirror of the account's default currency (T-55). [SessionState]'s
 * EncryptedSharedPreferences backing isn't observable, so a currency change made in Settings
 * wouldn't reach an already-open list screen without this — every writer (login, Settings) calls
 * [set] alongside persisting to [SessionState], and readers that need live updates (e.g.
 * ListViewModel) collect [currency] instead of reading `SessionState.defaultCurrency` directly.
 */
@Singleton
class DefaultCurrencyState @Inject constructor(sessionState: SessionState) {
    private val _currency = MutableStateFlow(sessionState.defaultCurrency)
    val currency: StateFlow<String?> = _currency.asStateFlow()

    fun set(value: String?) {
        _currency.value = value
    }
}
