package org.p23q.shoppinglist.ui.expense

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.AppFormat
import org.p23q.shoppinglist.data.ExpenseMath
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.ui.LocalizedOverlay
import org.p23q.shoppinglist.ui.appLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Add or edit one expense (T-154). Full-screen with a fixed action bar for the same reason
 * AddItemDialog is (T-80): the soft keyboard must never cover the buttons.
 *
 * [itemId] null means a new expense on [listId]; otherwise that expense is edited. [prefill]
 * seeds a new expense with what Reimburse on the balances screen chose (T-165).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseDialog(
    listId: String,
    itemId: String?,
    onDismiss: () -> Unit,
    prefill: ExpensePrefill? = null,
    viewModel: ExpenseFormViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nameFocusRequester = remember { FocusRequester() }
    var isPickingDate by remember { mutableStateOf(false) }
    // A read-only field consumes its own taps, so a tap is read from its interactions instead.
    val dateInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(dateInteraction) {
        dateInteraction.interactions.collect { if (it is PressInteraction.Release) isPickingDate = true }
    }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(listId, itemId) {
        if (itemId == null) viewModel.startAdd(listId, prefill) else viewModel.startEdit(itemId)
    }
    LaunchedEffect(state.isSaved, state.isDeleted) {
        if (state.isSaved || state.isDeleted) onDismiss()
    }
    // Open and start typing. The frame wait is load-bearing: this dialog is a window of its own,
    // and a focus request placed before that window exists is dropped, taking the keyboard with it.
    LaunchedEffect(Unit) {
        if (itemId == null) {
            withFrameNanos {}
            runCatching { nameFocusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.let { WindowCompat.setDecorFitsSystemWindows(it, false) } }

        // A window of its own, so the chosen language has to be applied again (T-131).
        LocalizedOverlay {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                        Text(
                            stringResource(if (state.isEditMode) R.string.expense_edit else R.string.expense_new),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                    }
                    HorizontalDivider()

                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    ) {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = viewModel::onNameChange,
                            label = { Text(stringResource(R.string.expense_what)) },
                            singleLine = true,
                            isError = state.nameError,
                            modifier = Modifier.fillMaxWidth().focusRequester(nameFocusRequester),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.totalText,
                                onValueChange = viewModel::onTotalChange,
                                label = { Text(stringResource(R.string.expense_total_with_currency, state.currency)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            // Picked, not typed (T-185), as the web's date input is: shown in the app's
                            // language, opened by a tap anywhere on the field or on its icon.
                            OutlinedTextField(
                                value = AppFormat.calendarDate(state.date, appLocale()),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.expense_date)) },
                                trailingIcon = {
                                    IconButton(onClick = { isPickingDate = true }) {
                                        Icon(Icons.Default.DateRange, contentDescription = stringResource(R.string.expense_date))
                                    }
                                },
                                singleLine = true,
                                interactionSource = dateInteraction,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        if (!state.soloList) {
                            Spacer(Modifier.height(16.dp))
                            ShareSection(
                                title = stringResource(R.string.expense_paid_by),
                                rows = state.paidBy,
                                error = state.paidByError,
                                sumCents = state.paidBySumCents,
                                onToggle = { viewModel.toggleParticipant(Side.PAID_BY, it) },
                                onChange = { id, value -> viewModel.onShareChange(Side.PAID_BY, id, value) },
                                onUseSum = { viewModel.useSumAsTotal(Side.PAID_BY) },
                            )
                            Spacer(Modifier.height(16.dp))
                            ShareSection(
                                title = stringResource(R.string.expense_paid_for),
                                rows = state.paidFor,
                                error = state.paidForError,
                                sumCents = state.paidForSumCents,
                                onToggle = { viewModel.toggleParticipant(Side.PAID_FOR, it) },
                                onChange = { id, value -> viewModel.onShareChange(Side.PAID_FOR, id, value) },
                                onUseSum = { viewModel.useSumAsTotal(Side.PAID_FOR) },
                            )
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.expense_solo_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = state.note,
                            onValueChange = viewModel::onNoteChange,
                            label = { Text(stringResource(R.string.item_note)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.isEditMode) {
                            Spacer(Modifier.height(16.dp))
                            TextButton(onClick = viewModel::requestDelete) {
                                Text(stringResource(R.string.action_delete))
                            }
                        }
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { viewModel.save() }, enabled = state.canSave) {
                            Text(stringResource(if (state.isEditMode) R.string.action_save else R.string.action_add))
                        }
                    }
                }
            }

            if (isPickingDate) {
                // Stored dates are calendar dates, so the picker works at UTC midnight both ways:
                // no time zone can move the chosen day.
                val pickerState = rememberDatePickerState(
                    initialSelectedDateMillis = runCatching {
                        LocalDate.parse(state.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    }.getOrNull(),
                )
                // Its own window, so the chosen language is applied again, as LocalizedAlertDialog does.
                DatePickerDialog(
                    onDismissRequest = { isPickingDate = false },
                    confirmButton = {
                        LocalizedOverlay {
                            TextButton(onClick = {
                                pickerState.selectedDateMillis?.let { millis ->
                                    viewModel.onDateChange(
                                        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString(),
                                    )
                                }
                                isPickingDate = false
                            }) { Text(stringResource(R.string.action_ok)) }
                        }
                    },
                    dismissButton = {
                        LocalizedOverlay {
                            TextButton(onClick = { isPickingDate = false }) { Text(stringResource(R.string.action_cancel)) }
                        }
                    },
                ) {
                    LocalizedOverlay { DatePicker(state = pickerState) }
                }
            }

            if (state.isDeleteConfirmOpen) {
                LocalizedAlertDialog(
                    onDismissRequest = viewModel::cancelDelete,
                    title = { Text(stringResource(R.string.expense_delete_title)) },
                    text = { Text(stringResource(R.string.expense_delete_body)) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmDelete() }) {
                            Text(stringResource(R.string.action_delete))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::cancelDelete) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ShareSection(
    title: String,
    rows: List<ShareRow>,
    error: ExpenseMath.DistributeError?,
    sumCents: Long,
    onToggle: (String) -> Unit,
    onChange: (String, String) -> Unit,
    onUseSum: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    rows.forEach { row ->
        val label = row.email
            ?: stringResource(R.string.expense_former_member, row.formerNumber)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = row.selected,
                enabled = !row.frozen,
                onCheckedChange = { onToggle(row.accountId) },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (row.frozen) {
                    Text(
                        stringResource(R.string.expense_frozen),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = row.text,
                onValueChange = { onChange(row.accountId, it) },
                // The derived share is the PLACEHOLDER, never the value: as the value it would come
                // straight back when the field was cleared, so typing over it appended to it.
                placeholder = { Text(ExpenseMath.fromCents(row.derivedCents)) },
                enabled = row.selected && !row.frozen,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(120.dp),
            )
        }
    }
    when (error) {
        ExpenseMath.DistributeError.FIXED_EXCEEDS_TOTAL ->
            Text(
                stringResource(R.string.expense_error_above_total),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        ExpenseMath.DistributeError.FIXED_SUM_MISMATCH -> {
            Text(
                stringResource(R.string.expense_error_does_not_add_up, ExpenseMath.fromCents(sumCents)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            // The one-tap way out, for when that sum was the intended total all along.
            TextButton(onClick = onUseSum) {
                Text(stringResource(R.string.expense_error_use_sum, ExpenseMath.fromCents(sumCents)))
            }
        }
        ExpenseMath.DistributeError.NO_PARTICIPANTS ->
            Text(
                stringResource(R.string.expense_error_nobody),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        ExpenseMath.DistributeError.TOTAL_NOT_POSITIVE, null -> Unit
    }
}
