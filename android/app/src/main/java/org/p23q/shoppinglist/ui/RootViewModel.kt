package org.p23q.shoppinglist.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.AuthRepository
import org.p23q.shoppinglist.data.api.SessionEvents
import javax.inject.Inject

/**
 * Backs the app-root collector in [ShoppingListNavHost]: exposes the [SessionEvents.forcedLogout]
 * signal and the local-session clear that must run before routing back to Login.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    sessionEvents: SessionEvents,
    private val authRepository: AuthRepository,
) : ViewModel() {

    val forcedLogout: SharedFlow<Unit> = sessionEvents.forcedLogout

    /** Clears the (already server-rejected) session locally; returns the Job so callers can await it. */
    fun onForcedLogout(): Job = viewModelScope.launch { authRepository.clearLocalSession() }
}
