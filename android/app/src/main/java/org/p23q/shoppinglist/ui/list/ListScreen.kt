package org.p23q.shoppinglist.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.CategoryCanon
import org.p23q.shoppinglist.core.DueDate
import org.p23q.shoppinglist.core.DueState
import org.p23q.shoppinglist.core.api.MemberDto
import org.p23q.shoppinglist.core.db.ItemEntity
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.ui.AddFab
import org.p23q.shoppinglist.ui.ErrorText
import org.p23q.shoppinglist.ui.appLocale
import org.p23q.shoppinglist.ui.shortDate
import java.time.LocalDate
import org.p23q.shoppinglist.ui.asString
import org.p23q.shoppinglist.ui.theme.accentText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onAddItem: () -> Unit,
    onEditItem: (itemId: String) -> Unit,
    onOpenRegistry: () -> Unit = {},
    onOpenListProps: () -> Unit = {},
    viewModel: ListViewModel = hiltViewModel(),
    /** Today's date, what the due dates are read against: a seam for the screenshot tests. */
    currentDate: () -> LocalDate = LocalDate::now,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Five-second refresh while this list is on screen (T-128), so two people shopping together
    // see each other's picks unattended. repeatOnLifecycle(RESUMED) is what makes "while the app
    // is active" literal: the loop is cancelled the moment the app backgrounds or the screen
    // leaves, so a pocketed phone makes no requests.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.liveSyncLoop()
        }
    }
    // What the due dates are read against (T-323): taken again on every return to the screen and
    // each minute while it is up, so a list left open over midnight turns its dates over.
    var today by remember { mutableStateOf(currentDate().toString()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                today = currentDate().toString()
                delay(60_000)
            }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }

    val undoLabel = stringResource(R.string.action_undo)
    val checkedTemplate = stringResource(R.string.list_item_checked)
    val checkedMessage = { name: String -> String.format(checkedTemplate, name) }
    LaunchedEffect(state.undoItemId) {
        val name = state.undoItemName
        val itemId = state.undoItemId
        if (itemId != null && name != null) {
            val result = snackbarHostState.showSnackbar(
                // Hoisted above the effect: this body is a coroutine, not a composition, so it
                // cannot call stringResource itself.
                message = checkedMessage(name),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoCheckOff()
            } else {
                viewModel.dismissUndo()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Bottom right on every list kind (T-168), where the expense list already had it.
        floatingActionButton = { AddFab(onClick = onAddItem, contentDescription = stringResource(R.string.list_add_item)) },
        // This screen already sits inside AppDrawerScaffold's Scaffold (which insets for the top
        // bar); without this, this inner Scaffold re-applies the status-bar inset and the controls
        // sit a status-bar-height too low, leaving empty space up top.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // Slim controls row: show-checked toggle-button on the left; the registry and
            // list-settings actions on the right (T-35). stringResource(R.string.list_clear_checked) moved into list properties
            // (T-75) — too easy to tap here by accident.
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.showChecked,
                    onClick = { viewModel.toggleShowChecked() },
                    label = { Text(stringResource(R.string.list_show_checked)) },
                    // Material's FilterChip shows no check unless given one; the web's toggle has
                    // always shown "✓" when on, and so does the expense list's selector (T-174).
                    leadingIcon = if (state.showChecked) {
                        {
                            Icon(
                                imageVector = Icons.Default.Done,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize).testTag("show-checked-mark"),
                            )
                        }
                    } else {
                        null
                    },
                )
                // The sync dot lives in the top bar now, on every screen as on the web (T-178).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenRegistry, modifier = Modifier.size(40.dp)) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.nav_registry))
                    }
                    IconButton(onClick = onOpenListProps, modifier = Modifier.size(40.dp)) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = stringResource(R.string.nav_list_properties))
                    }
                }
            }
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Said, not left blank (T-179), as the expense list and the web do.
                    // Muted and at the start, the one style every empty state has (T-339).
                    if (state.groups.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.list_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("list-empty"),
                            )
                        }
                    }
                    // The uncategorised group's heading (T-339): none when it is the only group, a
                    // lone dash says nothing there; otherwise a muted dash that TalkBack reads as
                    // "No category" rather than as a dash.
                    val onlyUncategorised = state.groups.size == 1 && state.groups[0].category == null
                    state.groups.forEachIndexed { index, group ->
                    // A distinct sentinel for "uncategorized" (T-263): group.category is null for
                    // that bucket and the canonical category text otherwise, but a user can also
                    // name a category literally "—" (CategoryCanon.UNCATEGORIZED_LABEL, used below
                    // only for display) — keying both on that same dash gave Compose two groups
                    // with the identical key and it crashed with "Key ... was already used".
                    if (!onlyUncategorised) item(key = group.category?.let { "header-cat:$it" } ?: "header-uncategorized") {
                        // A thin divider between categories (not above the first) makes groups easy to
                        // tell apart; the header itself gets a colored accent.
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        val noCategory = stringResource(R.string.list_no_category)
                        Text(
                            text = group.category ?: CategoryCanon.UNCATEGORIZED_LABEL,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (group.category == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.accentText
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 3.dp)
                                .testTag(if (group.category == null) "group-heading-uncategorized" else "group-heading")
                                .semantics {
                                    heading()
                                    if (group.category == null) contentDescription = noCategory
                                },
                        )
                    }
                    itemsIndexed(group.items, key = { _, it -> it.localId }) { itemIndex, item ->
                        ItemRow(
                            showShoppingFields = state.showShoppingFields,
                            showDueDate = state.showDueDate,
                            today = today,
                            item = item,
                            exiting = item.localId in state.exitingItemIds,
                            defaultCurrency = state.defaultCurrency,
                            // Only when the list has 2+ members (T-64) — no clutter for the common
                            // solo case, where "who touched this" has exactly one possible answer.
                            authorMember = if (state.members.size >= 2) {
                                state.members.find { it.accountId == item.lastTouchedByAccountId }
                            } else {
                                null
                            },
                            onToggle = {
                                if (item.status.value == Status.CHECKED.wireValue) {
                                    viewModel.uncheck(item.localId)
                                } else {
                                    viewModel.checkOff(item.localId)
                                }
                            },
                            onEdit = { onEditItem(item.localId) },
                        )
                        // A slightly-visible line between each item within a group (T-78). Skipped
                        // after the last one so it doesn't stack with the next category's divider.
                        if (itemIndex < group.items.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
                    // Room below the last row, so the floating Add button never covers it.
                    item(key = "fab-clearance") { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    /** False on a checklist (T-110): suppresses the quantity/price detail line. */
    showShoppingFields: Boolean,
    /** True on a checklist only (T-323): the item's due date, if any, at the row's trailing edge. */
    showDueDate: Boolean,
    /** Today's calendar date, `YYYY-MM-DD`, which the due date is read against. */
    today: String,
    item: ItemEntity,
    defaultCurrency: String?,
    authorMember: MemberDto?,
    /** True while this row is animating away after being checked off (T-128). It is already
     *  logically gone — still on screen only so the exit can be seen — so it is inert. */
    exiting: Boolean = false,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val isChecked = item.status.value == Status.CHECKED.wireValue

    // Toward the inline START, so it mirrors under RTL (T-126): sliding left is right in English
    // and wrong in Arabic. slideOutHorizontally takes a physical offset, so the direction is
    // resolved from the layout rather than hardcoded.
    val towardStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1
    // 120 ms of struck-through-but-still-there so the strike is readable, then a 180 ms slide.
    val slide = tween<IntOffset>(durationMillis = 180, delayMillis = 120)
    val collapse = tween<IntSize>(durationMillis = 180, delayMillis = 120)
    val dim = tween<Float>(durationMillis = 180, delayMillis = 120)

    AnimatedVisibility(
        visible = !exiting,
        // No enter transition: rows appear as part of a normal list update, and animating every
        // arrival would make an ordinary sync look busy.
        enter = EnterTransition.None,
        exit = slideOutHorizontally(animationSpec = slide) { width -> towardStart * width } +
            shrinkVertically(animationSpec = collapse) +
            fadeOut(animationSpec = dim),
    ) {
    val editLabel = stringResource(R.string.list_edit_item, item.name.value)
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Tap toggles done; long-press opens the item editor instead of toggling it (T-79).
                // A checkbox to TalkBack (T-339), so it says whether the item is checked, as the web's
                // role="checkbox" row does.
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = onEdit,
                    onLongClickLabel = editLabel,
                    role = Role.Checkbox,
                )
                .semantics { toggleableState = ToggleableState(isChecked) }
                .testTag("item-row")
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name.value,
                    // One line: whatever sits at the trailing edge (the due date, the badge) never
                    // pushes the name onto a second line; the name is what gives way.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (isChecked) {
                        // Theme-aware (was a hardcoded Color.Red with poor dark-theme contrast — T-40).
                        // The strike itself now spans the whole row (below), not just this text.
                        MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.error)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                )
                // Quantity is the thing you need in-store ("2l milk"), so show it alongside the price.
                // Suppressed on a checklist (T-110): a converted list can still hold these values, and
                // showing what the form won't let you edit would be confusing. The data is untouched.
                val quantity = item.quantity.value?.takeIf { it.isNotBlank() }
                val detail = if (!showShoppingFields) "" else
                    listOfNotNull(quantity, formatPrice(item, defaultCurrency, appLocale())).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    // Muted, as every secondary line is (T-339).
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("item-detail"),
                    )
                }
                item.note.value?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The server refused this row and the device parked it (T-32). Until T-210 the only
                // signal was the Overview's attention banner, which lands on the list and leaves the
                // user to guess which item; the expense list has said it on the row since T-200, so
                // say it here the same way. The web needs no equivalent: it pushes synchronously and
                // reports the refusal at save time.
                if (item.syncBlocked) {
                    val notSaved = stringResource(R.string.expense_not_saved)
                    // No participant to name on an item, so the codes that name one cannot arise.
                    val reason = ErrorText.refusal(item.syncBlockedCode, null)?.asString()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The icon carries the "not saved" half for anyone who cannot see the colour,
                        // which leaves the line itself for the reason.
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = notSaved,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = reason ?: notSaved,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            val due = item.due.value
            if (showDueDate && due != null) {
                DueDateText(due = due, today = today, isChecked = isChecked)
                Spacer(Modifier.width(4.dp))
            }
            if (authorMember != null) {
                AuthorBadge(authorMember)
                Spacer(Modifier.width(4.dp))
            }
            // The full 48dp target (T-339).
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp).testTag("item-edit")) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = editLabel)
            }
        }
        // A per-word LineThrough only crossed the name, leaving quantity/note/icon untouched; one
        // line across the whole row reads more clearly as "done" (T-63).
        if (isChecked) {
            HorizontalDivider(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 16.dp)
                    .testTag("checked-item-strike"),
                thickness = 1.5.dp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    }
}

/**
 * An item's due date as a sidenote (T-323): short, small and muted, with colour its only emphasis:
 * the error colour once it is past, the highlighted text colour on the day, muted otherwise, and
 * muted on a checked item whatever the date. What the colour says is also its accessibility
 * description.
 */
@Composable
private fun DueDateText(due: String, today: String, isChecked: Boolean) {
    val state = DueDate.state(due, today)
    val color = when {
        isChecked -> MaterialTheme.colorScheme.onSurfaceVariant
        state == DueState.OVERDUE -> MaterialTheme.colorScheme.error
        state == DueState.TODAY -> MaterialTheme.colorScheme.accentText
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = shortDate(due, appLocale(), today)
    val meaning = when (state) {
        DueState.OVERDUE -> stringResource(R.string.item_due_overdue)
        DueState.TODAY -> stringResource(R.string.item_due_today)
        DueState.UPCOMING -> null
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.let { it.copy(color = color, fontSize = it.fontSize * 0.8f) },
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .testTag("item-due")
            .semantics { contentDescription = if (meaning != null) "$text, $meaning" else text },
    )
}

/** Small initials circle for "who last touched this" (T-64); the full email rides as the
 *  accessibility content description since there's no hover on touch devices. */
@Composable
private fun AuthorBadge(member: MemberDto) {
    val touchedByDescription = stringResource(R.string.list_last_touched_by, member.email)
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .semantics { contentDescription = touchedByDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = member.initials,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
