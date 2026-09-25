package org.p23q.shoppinglist.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.AppFormat
import org.p23q.shoppinglist.core.ListKind
import org.p23q.shoppinglist.core.api.InviteForMeDto
import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.core.db.ListEntity
import org.p23q.shoppinglist.data.label
import org.p23q.shoppinglist.ui.AddFab
import org.p23q.shoppinglist.ui.CompactButtonPadding
import org.p23q.shoppinglist.ui.LocalizedAlertDialog
import org.p23q.shoppinglist.ui.SyncStatusBar
import org.p23q.shoppinglist.ui.appLocale
import org.p23q.shoppinglist.ui.accountName
import org.p23q.shoppinglist.ui.asString
import org.p23q.shoppinglist.ui.expense.balanceColor
import org.p23q.shoppinglist.ui.rememberTickingNowMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    onOpenList: (listId: String) -> Unit,
    /** A signed-out account's banner was tapped: sign that account in again (T-292). */
    onSignIn: (accountId: String) -> Unit = {},
    /**
     * An outdated account's banner was tapped: ask the servers for a newer app, whatever the
     * automatic check is set to, and offer it (T-304).
     */
    onCheckForUpdate: () -> Unit = {},
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowMs = rememberTickingNowMs()

    // A Join that went through opens the list, as redeeming a pasted link does (T-233).
    LaunchedEffect(state.joinedListId) {
        state.joinedListId?.let { listId ->
            viewModel.joinedListOpened()
            onOpenList(listId)
        }
    }

    Scaffold(
        floatingActionButton = {
            // The same Add button as every list (T-168), in the primary colours, not Material's
            // paler default (T-174).
            AddFab(onClick = viewModel::openCreateDialog, contentDescription = stringResource(R.string.overview_new_list))
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // The recency line moved into the top bar with the status dot (T-178); what stays here is
            // the banner for rows that failed to sync, which needs the room and the tap target.
            SyncStatusBar(
                state = state.sync,
                nowMs = nowMs,
                onAttentionClick = { state.attentionListId?.let(onOpenList) },
                showRecency = false,
            )
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                val sections = state.sections
                val hasBanner = sections.any { accountBanner(it.account) != null }
                if (state.lists.isEmpty() && state.invites.isEmpty() && !hasBanner) {
                    // Scrollable so the pull gesture still fires with no lists to scroll.
                    Box(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.overview_no_lists))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        // One section per account (T-292). With a single account it has no header
                        // and its cards no marker: the screen is the one-account overview it was.
                        sections.forEachIndexed { index, section ->
                            val account = section.account
                            if (state.several) {
                                item(key = "account-" + account.id) {
                                    AccountHeader(account, first = index == 0)
                                }
                            }
                            accountBanner(account)?.let { banner ->
                                item(key = "banner-" + account.id) {
                                    AccountBanner(
                                        text = stringResource(banner),
                                        onClick = when (banner) {
                                            R.string.overview_account_signed_out -> { { onSignIn(account.id) } }
                                            R.string.overview_account_outdated -> onCheckForUpdate
                                            else -> null
                                        },
                                    )
                                }
                            }
                            if (section.lists.isEmpty() && (state.several || state.invites.isNotEmpty() || hasBanner)) {
                                // Only invites (or nothing) to show: say the lists are empty where they would be.
                                item(key = "no-lists-" + account.id) {
                                    Text(
                                        stringResource(R.string.overview_no_lists),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(section.lists, key = { it.localId }) { list ->
                                ListCard(
                                    list = list,
                                    accountMarker = accountMarker(account).takeIf { state.several },
                                    summary = state.expenseSummaries[list.localId],
                                    openCount = state.openCounts[list.localId] ?: 0,
                                    onClick = { onOpenList(list.localId) },
                                )
                            }
                            // Invites waiting for this account (T-233), below its lists so what you
                            // have comes first. Ignoring is this device's choice alone: the card moves
                            // to the greyed part at the bottom of the section, where Join is still offered.
                            if (section.invites.isNotEmpty()) {
                                item(key = "invites-heading-" + account.id) {
                                    SectionHeading(stringResource(R.string.overview_invites), tag = "invites-heading-" + account.id)
                                }
                                items(section.invites, key = { "invite-" + account.id + "-" + it.id }) { invite ->
                                    InviteCard(
                                        invite = invite,
                                        nowMs = nowMs,
                                        ignored = false,
                                        busy = state.joiningInviteId != null,
                                        onJoin = { viewModel.joinInvite(account.id, invite) },
                                        onIgnore = { viewModel.ignoreInvite(account.id, invite.id) },
                                    )
                                }
                            }
                            if (state.inviteErrorAccountId == account.id) {
                                state.inviteError?.let { error ->
                                    item(key = "invite-error-" + account.id) {
                                        Text(
                                            error.asString(),
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                            if (section.ignoredInvites.isNotEmpty()) {
                                item(key = "ignored-heading-" + account.id) {
                                    SectionHeading(
                                        stringResource(R.string.overview_invites_ignored),
                                        tag = "ignored-heading-" + account.id,
                                        muted = true,
                                    )
                                }
                                items(section.ignoredInvites, key = { "ignored-" + account.id + "-" + it.id }) { invite ->
                                    InviteCard(
                                        invite = invite,
                                        nowMs = nowMs,
                                        ignored = true,
                                        busy = state.joiningInviteId != null,
                                        onJoin = { viewModel.joinInvite(account.id, invite) },
                                        onIgnore = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.isCreateDialogOpen) {
        NewListDialog(
            state = state,
            onNameChange = viewModel::onNewListNameChange,
            onKindChange = viewModel::onNewListKindChange,
            onCurrencyChange = viewModel::onNewListCurrencyChange,
            onAccountChange = viewModel::onNewListAccountChange,
            onCreate = { viewModel.createList() },
            onDismiss = viewModel::dismissCreateDialog,
        )
    }
}

/** The New-list dialog: name, kind, an expenses list's currency and, with several accounts, whose list it is. */
@Composable
internal fun NewListDialog(
    state: OverviewUiState,
    onNameChange: (String) -> Unit,
    onKindChange: (String) -> Unit,
    onCurrencyChange: (String) -> Unit,
    onAccountChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    LocalizedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.overview_new_list)) },
        text = {
            Column {
                // Which account the list goes to (T-292), only when there is a choice. Fixed for
                // the list's life: a list never moves between accounts.
                if (state.several) {
                    Text(stringResource(R.string.overview_new_list_account), style = MaterialTheme.typography.labelMedium)
                    state.accounts.forEach { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = state.newListAccountId == account.id,
                                    onClick = { onAccountChange(account.id) },
                                )
                                .padding(vertical = 4.dp)
                                .testTag("new-list-account-" + account.id),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.newListAccountId == account.id,
                                onClick = { onAccountChange(account.id) },
                            )
                            AccountLines(account)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = state.newListName,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.overview_list_name)) },
                    singleLine = true,
                    isError = state.newListNameMissing,
                    supportingText = if (state.newListNameMissing) {
                        { Text(stringResource(R.string.overview_name_required)) }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("new-list-name"),
                )
                Spacer(Modifier.height(12.dp))
                // Kind is chosen up front (T-110) but isn't permanent — list properties can
                // convert it later, and converting never touches item data.
                Text(stringResource(R.string.overview_type), style = MaterialTheme.typography.labelMedium)
                // No ledger in the local area (T-293): its lists are never shared.
                state.newListKinds.forEach { kind ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.newListKind == kind,
                                onClick = { onKindChange(kind) },
                            )
                            .padding(vertical = 4.dp)
                            .testTag("new-list-kind-$kind"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.newListKind == kind,
                            onClick = { onKindChange(kind) },
                        )
                        Text("${ListKind.icon(kind)}  ${stringResource(ListKind.label(kind))}")
                    }
                }
                Text(
                    when (state.newListKind) {
                        ListKind.CHECKLIST -> stringResource(R.string.overview_kind_checklist)
                        ListKind.EXPENSES -> stringResource(R.string.overview_kind_expenses)
                        else -> stringResource(R.string.overview_kind_shopping)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Free text, not a picker: the server takes any label, so a group that settles
                // in pizza slices can say so. Fixed once the list exists.
                if (ListKind.isExpenses(state.newListKind)) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.newListCurrency,
                        onValueChange = onCurrencyChange,
                        label = { Text(stringResource(R.string.expense_currency)) },
                        singleLine = true,
                        isError = state.newListCurrencyMissing,
                        supportingText = if (state.newListCurrencyMissing) {
                            { Text(stringResource(R.string.overview_currency_required)) }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag("new-list-currency"),
                    )
                    Text(
                        stringResource(R.string.overview_currency_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The phone glyph that marks a card of the local area among several accounts (T-293). */
internal const val LOCAL_AREA_GLYPH = "📱"

/** What a card says of its account with several: the email, or the local area's phone glyph. */
private fun accountMarker(account: AccountEntity): String? = if (account.isServer) account.email else LOCAL_AREA_GLYPH

/** What an account's section says above its lists, if anything (T-292): signed out, or app too old. */
private fun accountBanner(account: AccountEntity): Int? = when {
    !account.isServer -> null
    account.outdated -> R.string.overview_account_outdated
    !account.signedIn -> R.string.overview_account_signed_out
    else -> null
}

/** An account as two lines: email, then the server's URL, muted (T-292). */
@Composable
private fun AccountLines(account: AccountEntity, emailStyle: TextStyle = MaterialTheme.typography.bodyMedium) {
    Column {
        Text(accountName(account), style = emailStyle)
        account.serverUrl?.let { url ->
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The small header over an account's section, with several accounts (T-292): "email  ·  server" on
 * one line, the server muted; only when that does not fit is the server put on a second line, then
 * without the dot (T-307). Slim: a small gap above it to part it from the previous section's last
 * card, none below, where the cards' own padding separates it from its first one.
 */
@Composable
internal fun AccountHeader(account: AccountEntity, first: Boolean) {
    val server = account.serverUrl
    Layout(
        content = {
            Text(accountName(account), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("account-header-email-" + account.id))
            if (server != null) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text("  ·  ", style = MaterialTheme.typography.bodySmall, color = muted)
                Text(server, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.testTag("account-header-server-" + account.id))
            }
        },
        modifier = Modifier
            .testTag("account-header-" + account.id)
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 12.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val email = measurables[0].measure(loose)
        if (measurables.size == 1) {
            return@Layout layout(constraints.maxWidth, email.height) { email.placeRelative(0, 0) }
        }
        val dot = measurables[1].measure(loose)
        val serverText = measurables[2].measure(loose)
        if (email.width + dot.width + serverText.width <= constraints.maxWidth) {
            // One line, the smaller server text on the email's baseline.
            val baseline = maxOf(email[FirstBaseline], dot[FirstBaseline], serverText[FirstBaseline])
            val top = { p: Placeable -> baseline - p[FirstBaseline] }
            val height = maxOf(top(email) + email.height, top(dot) + dot.height, top(serverText) + serverText.height)
            layout(constraints.maxWidth, height) {
                email.placeRelative(0, top(email))
                dot.placeRelative(email.width, top(dot))
                serverText.placeRelative(email.width + dot.width, top(serverText))
            }
        } else {
            layout(constraints.maxWidth, email.height + serverText.height) {
                email.placeRelative(0, 0)
                serverText.placeRelative(0, email.height)
            }
        }
    }
}

/** A one-line notice at the top of an account's section; tappable when it offers something. */
@Composable
private fun AccountBanner(text: String, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
    val modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = modifier,
            content = content,
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = modifier,
            content = content,
        )
    }
}

/** One list on the overview: kind, name (and, with several accounts, its account), then its figures. */
@Composable
private fun ListCard(
    list: ListEntity,
    accountMarker: String?,
    summary: ExpenseSummary?,
    openCount: Int,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ListKind.icon(list.kind.value),
                modifier = Modifier.padding(end = 8.dp),
            )
            if (accountMarker == null) {
                Text(text = list.name.value, modifier = Modifier.weight(1f))
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = list.name.value)
                    Text(
                        text = accountMarker,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (summary != null) {
                // What has been spent, and where this account stands —
                // an expenses list has no open items to count (T-154).
                Column(horizontalAlignment = Alignment.End) {
                    val locale = appLocale()
                    val total = AppFormat.money(summary.totalCents, summary.currency, locale)
                    Text(
                        // "Closed · total", as the web writes it (T-181).
                        text = if (summary.closed) {
                            "${stringResource(R.string.expense_closed)} · $total"
                        } else {
                            total
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    summary.myBalanceCents?.let { balance ->
                        Text(
                            text = AppFormat.signedMoney(balance, summary.currency, locale),
                            style = MaterialTheme.typography.bodySmall,
                            // Square is grey, as everywhere else (T-182).
                            color = balanceColor(balance),
                        )
                    }
                }
            }
            if (openCount > 0) {
                // Room between an expense list's total/balance and its count
                // (T-191), which otherwise sat right against them.
                if (summary != null) Spacer(Modifier.width(12.dp))
                // At-a-glance "is a trip pending" count of open items (T-42);
                // on an expense list, its number of expenses.
                Text(
                    text = openCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The Invitations and Ignored headings in an account's section (T-233). At the end of the line,
 * which is how they are told from the account's own header at its start, and slim as that one is
 * (T-307): a 12dp gap above, nothing below, one line (T-309).
 */
@Composable
internal fun SectionHeading(text: String, tag: String, muted: Boolean = false) {
    Box(
        modifier = Modifier.testTag(tag).fillMaxWidth().padding(top = 12.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Text(
            text,
            modifier = Modifier.testTag("$tag-text"),
            style = MaterialTheme.typography.titleSmall,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One invite on the overview (T-233): kind, list name, who invited and how long it stands, then Ignore and Join. */
@Composable
private fun InviteCard(
    invite: InviteForMeDto,
    nowMs: Long,
    ignored: Boolean,
    busy: Boolean,
    onJoin: () -> Unit,
    onIgnore: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            // Greyed once ignored, as the web does it; the card otherwise reads the same.
            .alpha(if (ignored) 0.6f else 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = ListKind.icon(invite.listKind), modifier = Modifier.padding(end = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = invite.listName)
                Text(
                    // "From AL · Expires in 5 d", as the web writes it.
                    text = stringResource(R.string.overview_invite_from, invite.invitedByInitials) +
                        " · " + formatExpiresIn(invite.expiresAt, nowMs).asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!ignored) {
                OutlinedButton(onClick = onIgnore, enabled = !busy, contentPadding = CompactButtonPadding) {
                    Text(stringResource(R.string.action_ignore))
                }
                Spacer(Modifier.width(4.dp))
            }
            Button(onClick = onJoin, enabled = !busy, contentPadding = CompactButtonPadding) {
                Text(stringResource(R.string.action_join))
            }
        }
    }
}
