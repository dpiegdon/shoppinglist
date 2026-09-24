package org.p23q.shoppinglist.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.core.AuthRepository
import org.p23q.shoppinglist.core.api.ProtocolState
import org.p23q.shoppinglist.core.api.SessionEvents
import javax.inject.Inject

/**
 * Backs the app-root collector in [ShoppingListNavHost]: exposes the [SessionEvents.forcedLogout]
 * signal and the local-session clear that must run before routing back to Login, plus the
 * [ProtocolState.updateRequired] state that blocks the whole UI (T-240).
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    sessionEvents: SessionEvents,
    private val authRepository: AuthRepository,
    protocolState: ProtocolState = ProtocolState(),
) : ViewModel() {

    val forcedLogout: SharedFlow<Unit> = sessionEvents.forcedLogout

    /** This app is too old for its server (T-240): the root shows nothing else while it is set. */
    val updateRequired: StateFlow<Boolean> = protocolState.updateRequired

    /** Clears the (already server-rejected) session locally; returns the Job so callers can await it. */
    fun onForcedLogout(): Job = viewModelScope.launch { authRepository.clearLocalSession() }
}
