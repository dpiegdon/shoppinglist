package org.p23q.shoppinglist.ui.list

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.data.api.MemberDto
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.ui.SyncStatusMarker
import org.p23q.shoppinglist.ui.rememberTickingNowMs
import org.p23q.shoppinglist.data.db.Status
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.R
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onAddItem: () -> Unit,
    onEditItem: (itemId: String) -> Unit,
    onOpenRegistry: () -> Unit = {},
    onOpenListProps: () -> Unit = {},
    viewModel: ListViewModel = hiltViewModel(),
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
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Folded into this row instead of its own line (T-63): a quiet dot rather than a
                    // full "Synced 5 min ago" sentence; the sentence itself is still there as the
                    // content description for TalkBack. The loud attention banner is Overview's job.
                    SyncStatusMarker(state = state.sync, nowMs = rememberTickingNowMs())
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onOpenRegistry, modifier = Modifier.size(40.dp)) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.nav_registry))
                    }
                    IconButton(onClick = onOpenListProps, modifier = Modifier.size(40.dp)) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = stringResource(R.string.nav_list_properties))
                    }
                }
            }
            Button(
                onClick = onAddItem,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.list_add_item))
            }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    state.groups.forEachIndexed { index, group ->
                    item(key = "header-${group.category ?: "—"}") {
                        // A thin divider between categories (not above the first) makes groups easy to
                        // tell apart; the header itself gets a colored accent.
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        Text(
                            text = group.category ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                        )
                    }
                    itemsIndexed(group.items, key = { _, it -> it.id }) { itemIndex, item ->
                        ItemRow(
                            showShoppingFields = state.showShoppingFields,
                            item = item,
                            exiting = item.id in state.exitingItemIds,
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
                                    viewModel.uncheck(item.id)
                                } else {
                                    viewModel.checkOff(item.id)
                                }
                            },
                            onEdit = { onEditItem(item.id) },
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
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Tap toggles done; long-press opens the item editor instead of toggling it (T-79).
                .combinedClickable(onClick = onToggle, onLongClick = onEdit)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name.value,
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
                    listOfNotNull(quantity, formatPrice(item, defaultCurrency)).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(text = detail, style = MaterialTheme.typography.bodySmall)
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
            }
            if (authorMember != null) {
                AuthorBadge(authorMember)
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = stringResource(R.string.list_edit_item, item.name.value))
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

/** Small initials circle for "who last touched this" (T-64); the full email rides as the
 *  accessibility content description since there's no hover on touch devices. */
@Composable
private fun AuthorBadge(member: MemberDto) {
    val touchedByDescription = stringResource(R.string.list_last_touched_by, member.email)
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
            .semantics { contentDescription = touchedByDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = member.initials,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
