package org.p23q.shoppinglist.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.update.AvailableUpdate
import org.p23q.shoppinglist.data.update.UpdateChecker
import javax.inject.Inject

/** Drives the update prompt (T-135); the check itself lives in [UpdateChecker]. */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    private val _availableUpdate = MutableStateFlow<AvailableUpdate?>(null)
    val availableUpdate: StateFlow<AvailableUpdate?> = _availableUpdate.asStateFlow()

    /** Safe to call on every foreground: [UpdateChecker] owns the rate limit. */
    fun check(): Job = viewModelScope.launch {
        _availableUpdate.value = updateChecker.check()
    }

    /**
     * Closes the prompt and records the version as asked-about — called for BOTH answers, since
     * the question was put either way and asking again would defeat the once-per-version rule.
     */
    fun dismiss(): Job = viewModelScope.launch {
        val version = _availableUpdate.value?.version
        _availableUpdate.value = null
        if (version != null) updateChecker.markPrompted(version)
    }
}
