package org.p23q.shoppinglist.ui.expense

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import org.p23q.shoppinglist.core.AppFormat
import org.p23q.shoppinglist.core.ExpenseMath
import org.p23q.shoppinglist.core.ExpenseType
import org.p23q.shoppinglist.ui.BlockedBanner
import org.p23q.shoppinglist.ui.CompactButtonPadding
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.ui.LocalizedOverlay
import org.p23q.shoppinglist.ui.appLocale
import org.p23q.shoppinglist.ui.dangerButtonColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Add or edit one ledger entry (T-154, T-245). Full-screen with a fixed action bar for the same
 * reason AddItemDialog is (T-80): the soft keyboard must never cover the buttons.
 *
 * The type comes first, because it decides what the rest of the form means: an expense and an
 * income are the same two distributions read opposite ways, and a transfer has none at all.
 *
 * [itemId] null means a new entry on [listId]; otherwise that entry is edited. [prefill] seeds a
 * new one with what Reimburse on the balances screen chose (T-165).
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
                        ExpenseBlockedBanner(state)
                        // What kind of entry this is, before anything else: it decides what the
                        // rest of the form means (T-245).
                        TypeSection(
                            type = state.type,
                            canTransfer = state.canTransfer,
                            onChange = viewModel::onTypeChange,
                        )
                        Spacer(Modifier.height(16.dp))
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

                        // A transfer has nothing to split: it is one person handing money to another.
                        if (state.type == ExpenseType.TRANSFER) {
                            Spacer(Modifier.height(8.dp))
                            TransferPickers(
                                state = state,
                                onFrom = viewModel::onTransferFromChange,
                                onTo = viewModel::onTransferToChange,
                            )
                        } else if (!state.soloList) {
                            Spacer(Modifier.height(16.dp))
                            ShareSection(
                                // Income is the same form read the other way round: the money came
                                // in to someone and was credited to the others (T-245).
                                title = stringResource(
                                    if (state.type == ExpenseType.INCOME) {
                                        R.string.expense_received_by
                                    } else {
                                        R.string.expense_paid_by
                                    },
                                ),
                                rows = state.paidBy,
                                error = state.paidByError,
                                sumCents = state.paidBySumCents,
                                totalCents = ExpenseMath.toCents(state.totalText.trim()) ?: 0L,
                                onToggle = { viewModel.toggleParticipant(Side.PAID_BY, it) },
                                onChange = { id, value -> viewModel.onShareChange(Side.PAID_BY, id, value) },
                                onUseSum = { viewModel.useSumAsTotal(Side.PAID_BY) },
                            )
                            Spacer(Modifier.height(16.dp))
                            ShareSection(
                                title = stringResource(
                                    if (state.type == ExpenseType.INCOME) {
                                        R.string.expense_credited_to
                                    } else {
                                        R.string.expense_paid_for
                                    },
                                ),
                                rows = state.paidFor,
                                error = state.paidForError,
                                sumCents = state.paidForSumCents,
                                totalCents = ExpenseMath.toCents(state.totalText.trim()) ?: 0L,
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
                            Button(onClick = viewModel::requestDelete, enabled = state.canDelete, colors = dangerButtonColors()) {
                                Text(stringResource(R.string.action_delete))
                            }
                            if (!state.canDelete) {
                                Text(
                                    stringResource(R.string.expense_delete_blocked),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                        Spacer(Modifier.width(8.dp))
                        // An income or a transfer left untitled names itself, and only the screen
                        // can say what that name is in the app's language (T-245).
                        val typeLabel = stringResource(typeLabelOf(state.type))
                        Button(onClick = { viewModel.save(typeLabel) }, enabled = state.canSave) {
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

/**
 * The server refused this expense and the device has parked it (T-200). The races this covers —
 * someone votes to close while you are editing offline — are exactly when an explanation is owed.
 * The banner itself is shared with the item form (T-210); only who the refusal names is local.
 */
@Composable
private fun ExpenseBlockedBanner(state: ExpenseFormUiState) {
    if (!state.isBlocked) return
    // Labelled as the share rows label the same person: an email while they are a member, a number
    // once they have left (T-197).
    val row = (state.paidBy + state.paidFor).firstOrNull { it.accountId == state.blockedAccountId }
    val who = row?.let { participantLabel(it) }
    BlockedBanner(code = state.blockedCode, who = who)
}

/** The i18n key of a type's label — also the title a blank income or transfer falls back to. */
internal fun typeLabelOf(type: ExpenseType): Int = when (type) {
    ExpenseType.EXPENSE -> R.string.expense_type_expense
    ExpenseType.INCOME -> R.string.expense_type_income
    ExpenseType.TRANSFER -> R.string.expense_type_transfer
}

/**
 * Which of the three this entry is (T-245): Expense | Income | Transfer, the segmented control the
 * ledger design asks for, and the one this screen's own Entries | Balances selector already uses.
 *
 * Transfer is off a list with fewer than two people whose amounts can move — there is nobody to
 * pay — and the hint below says why rather than leaving a dead segment unexplained.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSection(type: ExpenseType, canTransfer: Boolean, onChange: (ExpenseType) -> Unit) {
    Text(stringResource(R.string.expense_type), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ExpenseType.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = type == option,
                onClick = { onChange(option) },
                enabled = option != ExpenseType.TRANSFER || canTransfer,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ExpenseType.entries.size),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(stringResource(typeLabelOf(option)), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (!canTransfer) {
        Text(
            stringResource(R.string.expense_error_need_two_members),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A transfer's two ends (T-245): who paid and who was paid, one person each. Neither picker offers
 * the other's choice — nobody pays themselves — nor anyone whose amounts are frozen, which the
 * server would refuse anyway; a stored entry that already names one keeps it, unchangeable.
 */
@Composable
private fun TransferPickers(
    state: ExpenseFormUiState,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TransferPicker(
            label = stringResource(R.string.expense_from),
            value = state.transferFrom,
            other = state.transferTo,
            participants = state.participants,
            onSelect = onFrom,
            modifier = Modifier.weight(1f),
        )
        TransferPicker(
            label = stringResource(R.string.expense_to),
            value = state.transferTo,
            other = state.transferFrom,
            participants = state.participants,
            onSelect = onTo,
            modifier = Modifier.weight(1f),
        )
    }
    if (state.sameMemberError) {
        Text(
            stringResource(R.string.expense_error_same_member),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun TransferPicker(
    label: String,
    value: String,
    /** The other end's choice, which this one does not offer. */
    other: String,
    participants: List<ShareRow>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val chosen = participants.firstOrNull { it.accountId == value }
    // A frozen participant's amounts may not move, so an entry that names one cannot be pointed
    // at somebody else here; the server would refuse it anyway.
    val enabled = chosen == null || !chosen.isFrozen

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = chosen?.let { participantLabel(it) }.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
                .padding(vertical = 8.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // A popup is its own window (T-131), so the chosen language has to be carried in.
            LocalizedOverlay {
                participants
                    .filter { (it.accountId != other || it.accountId == value) }
                    .filter { !it.isFrozen || it.accountId == value }
                    .forEach { row ->
                        DropdownMenuItem(
                            text = { Text(participantLabel(row)) },
                            onClick = {
                                expanded = false
                                onSelect(row.accountId)
                            },
                        )
                    }
            }
        }
    }
}

/** An email while they are a member, a stable number once they have left (T-152, T-197). */
@Composable
private fun participantLabel(row: ShareRow): String =
    row.email ?: stringResource(R.string.expense_former_member, row.formerNumber)

@Composable
private fun ShareSection(
    title: String,
    rows: List<ShareRow>,
    error: ExpenseMath.DistributeError?,
    sumCents: Long,
    /** For "add up to X, not Y" — the same message as the web's (T-148). */
    totalCents: Long,
    onToggle: (String) -> Unit,
    onChange: (String, String) -> Unit,
    onUseSum: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    rows.forEach { row ->
        val label = participantLabel(row)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = row.selected,
                enabled = !row.isFrozen,
                onCheckedChange = { onToggle(row.accountId) },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                // Why, not just that (T-203): "agreed to close" was said of everyone frozen, which
                // is untrue of someone who has simply left the list.
                val reason = when (row.frozen) {
                    FrozenReason.VOTER -> R.string.expense_frozen_voter
                    FrozenReason.FORMER -> R.string.expense_frozen_former
                    FrozenReason.NONE -> null
                }
                if (reason != null) {
                    Text(
                        stringResource(reason),
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
                enabled = row.selected && !row.isFrozen,
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
                stringResource(
                    R.string.expense_error_does_not_add_up,
                    ExpenseMath.fromCents(sumCents),
                    ExpenseMath.fromCents(totalCents),
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            // The one-tap way out, for when that sum was the intended total all along.
            Button(onClick = onUseSum, contentPadding = CompactButtonPadding) {
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
