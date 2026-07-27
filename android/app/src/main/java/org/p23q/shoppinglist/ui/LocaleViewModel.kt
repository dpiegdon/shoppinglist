package org.p23q.shoppinglist.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.AppLocale
import org.p23q.shoppinglist.data.LocalePreferenceStore
import org.p23q.shoppinglist.data.deviceLocale
import javax.inject.Inject

/**
 * Backs the language chooser (T-127) wherever it appears.
 *
 * A ViewModel of its own rather than a field on LoginViewModel and SettingsViewModel: the chooser
 * shows up on both, one of which is reachable without an account, and duplicating the same two
 * members into both would leave two places to keep in step for no gain.
 *
 * Seeded with the device language rather than a fixed default, so the control never flashes
 * "English" before the stored preference arrives.
 */
@HiltViewModel
class LocaleViewModel @Inject constructor(
    private val store: LocalePreferenceStore,
) : ViewModel() {

    val locale: StateFlow<AppLocale> = store.effective.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = deviceLocale(),
    )

    fun setLocale(locale: AppLocale) {
        viewModelScope.launch { store.setLocale(locale) }
    }
}
