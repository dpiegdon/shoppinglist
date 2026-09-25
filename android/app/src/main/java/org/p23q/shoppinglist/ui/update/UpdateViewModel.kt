package org.p23q.shoppinglist.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.p23q.shoppinglist.data.update.AvailableUpdate
import org.p23q.shoppinglist.data.update.CheckOutcome
import org.p23q.shoppinglist.data.update.UpdateChecker
import org.p23q.shoppinglist.data.update.UpdatePrefsStore
import javax.inject.Inject

/** What the About screen says about updates (T-149; it was the settings screen until T-224). */
sealed interface UpdateStatus {
    /** Nothing to say: not checked yet, or checking is switched off. */
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val version: String) : UpdateStatus
    data class Available(val version: String) : UpdateStatus
    data object Failed : UpdateStatus
}

/** Drives the update prompt (T-135); the check itself lives in [UpdateChecker]. */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateChecker: UpdateChecker,
    private val updatePrefs: UpdatePrefsStore,
) : ViewModel() {

    private val _availableUpdate = MutableStateFlow<AvailableUpdate?>(null)
    val availableUpdate: StateFlow<AvailableUpdate?> = _availableUpdate.asStateFlow()

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /**
     * The "check automatically" switch, read by the About screen that now owns it (T-224). It sat
     * on SettingsViewModel while the toggle sat in settings; it belongs with the rest of the
     * update wiring rather than being duplicated onto a second screen's view model.
     */
    val autoCheckEnabled: StateFlow<Boolean> =
        updatePrefs.autoCheckEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Device-local, like notifications (T-135); off means no request at all, not a silent check. */
    fun setAutoCheckEnabled(enabled: Boolean): Job = viewModelScope.launch {
        updatePrefs.setAutoCheckEnabled(enabled)
    }

    /** Safe to call on every foreground: [UpdateChecker] owns the rate limit. */
    fun check(): Job = viewModelScope.launch {
        _availableUpdate.value = updateChecker.check()
    }

    /**
     * Run when the About screen opens (T-149, settings until T-224): the same prompt as the
     * automatic check, but asked now, and with the answer shown on the screen whatever it is.
     */
    fun checkNow(): Job = viewModelScope.launch {
        _status.value = UpdateStatus.Checking
        _status.value = when (val outcome = updateChecker.checkNow()) {
            is CheckOutcome.Available -> {
                _availableUpdate.value = outcome.update
                UpdateStatus.Available(outcome.update.version)
            }
            is CheckOutcome.UpToDate -> UpdateStatus.UpToDate(outcome.version)
            CheckOutcome.Failed -> UpdateStatus.Failed
            CheckOutcome.NotChecked -> UpdateStatus.Idle
        }
    }

    /**
     * The check the blocking "update required" screen makes (T-244), and makes again on Retry.
     *
     * Reports through the same [status] and [availableUpdate] as the About screen's check, so the
     * screen offers the download exactly the way the ordinary prompt does — the difference is only
     * that [UpdateChecker.checkForced] asks whatever the switch and the interval say.
     */
    fun checkRequired(): Job = viewModelScope.launch {
        _status.value = UpdateStatus.Checking
        _status.value = when (val outcome = updateChecker.checkForced()) {
            is CheckOutcome.Available -> {
                _availableUpdate.value = outcome.update
                UpdateStatus.Available(outcome.update.version)
            }
            is CheckOutcome.UpToDate -> UpdateStatus.UpToDate(outcome.version)
            CheckOutcome.Failed -> UpdateStatus.Failed
            // "Could not be asked" — there is no server configured, the only thing that still
            // stops a forced check. Reported as a failed check rather than as Idle, which the
            // blocking screen reads as "still checking" and would sit on forever.
            CheckOutcome.NotChecked -> UpdateStatus.Failed
        }
    }

    private val _checkNotice = MutableStateFlow<UpdateStatus?>(null)

    /**
     * What [checkForOutdated] has to say when it has no update to offer: [UpdateStatus.UpToDate]
     * (no server offers a newer app than this one) or [UpdateStatus.Failed]; null otherwise.
     */
    val checkNotice: StateFlow<UpdateStatus?> = _checkNotice.asStateFlow()

    /**
     * "Check for update" on an outdated account's overview banner or Accounts row (T-304). With
     * another account working, or the local area here, the blocking screen does not show, and
     * with the automatic check off nothing else would ever ask. Forced like [checkRequired]: a
     * newer app is offered with the ordinary prompt, and anything else is a [checkNotice].
     */
    fun checkForOutdated(): Job = viewModelScope.launch {
        when (val outcome = updateChecker.checkForced()) {
            is CheckOutcome.Available -> _availableUpdate.value = outcome.update
            is CheckOutcome.UpToDate -> _checkNotice.value = UpdateStatus.UpToDate(outcome.version)
            CheckOutcome.Failed, CheckOutcome.NotChecked -> _checkNotice.value = UpdateStatus.Failed
        }
    }

    fun dismissCheckNotice() {
        _checkNotice.value = null
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
