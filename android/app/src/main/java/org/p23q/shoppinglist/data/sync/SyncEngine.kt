package org.p23q.shoppinglist.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.core.Expense
import org.p23q.shoppinglist.core.db.LwwBoolean
import org.p23q.shoppinglist.core.db.LwwOptionalString
import org.p23q.shoppinglist.core.db.LwwString
import org.p23q.shoppinglist.core.db.toLww
import org.p23q.shoppinglist.core.db.toLwwOptional
import org.p23q.shoppinglist.core.sync.CollaboratorChange
import org.p23q.shoppinglist.core.sync.CollaboratorChangeNotifier
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.api.ApiException
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.AppJson
import org.p23q.shoppinglist.data.api.FieldClock
import org.p23q.shoppinglist.data.api.ItemDto
import org.p23q.shoppinglist.data.api.ItemFieldsDto
import org.p23q.shoppinglist.data.api.ListDto
import org.p23q.shoppinglist.data.api.ListFieldsDto
import org.p23q.shoppinglist.data.api.PriceDto
import org.p23q.shoppinglist.data.api.ProtocolState
import org.p23q.shoppinglist.data.api.SyncChanges
import org.p23q.shoppinglist.data.api.SyncRequest
import org.p23q.shoppinglist.data.api.UnauthorizedException
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.ItemDao
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.ListDao
import org.p23q.shoppinglist.data.db.ListEntity
import org.p23q.shoppinglist.data.db.inTransaction
import java.io.IOException
import javax.inject.Inject
import javax.net.ssl.SSLException

sealed interface SyncResult {
    data class Success(val pushedItems: Int, val pushedLists: Int, val pulledItems: Int, val pulledLists: Int) : SyncResult
    data object Unauthorized : SyncResult
    /**
     * This app is too old for the server (T-240). Distinct from [Failed] because it must not be
     * retried: the server will refuse every request until the app is updated, and the update
     * prompt — not a backoff — is what resolves it.
     */
    data object UpdateRequired : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * Pushes dirty local rows and pulls remote changes in one round trip, per the Wire Contract's
 * /sync endpoint. Applying the response is a field-level LWW merge — NOT a blind overwrite —
 * because a local edit can race the request: the row we snapshotted as "dirty" may have been
 * edited again before the response comes back, and that newer local edit must survive.
 */
class SyncEngine @Inject constructor(
    private val itemDao: ItemDao,
    private val listDao: ListDao,
    private val apiProvider: ApiProvider,
    private val sessionState: SessionState,
    private val serverConfig: ServerConfig,
    private val appDb: AppDb,
    private val syncStatus: SyncStatus,
    private val notifier: CollaboratorChangeNotifier,
    // Last with a default for the same reason as ErrorInterceptor's: the tests that exercise the
    // 426 path pass the same instance the interceptor got, the rest need not know it exists.
    private val protocolState: ProtocolState = ProtocolState(),
) {
    companion object {
        /**
         * Server cap on rows per /sync push (sync.MAX_CHANGES_PER_SYNC, T-114). A larger batch is
         * rejected with `too_many_changes`, because applying one holds SQLite's single write lock
         * for its whole duration and an unbounded batch could stall every other write on the
         * instance. A backlog past this is pushed over several passes — see syncNow. Lowering this
         * is safe (smaller batches); raising it above the server's value is not.
         */
        const val MAX_CHANGES_PER_SYNC = 250
    }

    /**
     * Seeds [syncStatus]'s pending/blocked counts straight from the database (T-265) — no network,
     * no server round trip. Called once at process start so a cold start offline still shows what
     * a sync would report, instead of [SyncStatus]'s initial zeros sitting there until a sync
     * (which every scheduled trigger requires connectivity for) finally runs and writes real ones.
     */
    suspend fun seedStatus() {
        val pending = itemDao.dirtyRows().size + listDao.dirtyRows().size
        syncStatus.seed(pending = pending, blocked = blockedCount())
    }

    suspend fun syncNow(fullLists: List<String> = emptyList()): SyncResult {
        // Too old for this server (T-240): stop before reading, sending or reporting anything. The
        // dirty rows stay dirty and stay pushable — the app being outdated says nothing about
        // them, and they go out unchanged the moment an updated build talks to the server again.
        if (protocolState.updateRequired.value) return SyncResult.UpdateRequired

        val allDirtyItems = itemDao.dirtyRows()
        val allDirtyLists = listDao.dirtyRows()
        val pendingBefore = allDirtyItems.size + allDirtyLists.size

        // Take at most one batch's worth, lists first (T-114). Lists lead because the server
        // registers membership when it applies one, and an item naming a list the server has not
        // seen is refused — so the list must never arrive in a later pass than its items.
        val dirtyLists = allDirtyLists.take(MAX_CHANGES_PER_SYNC)
        val dirtyItems = allDirtyItems.take(MAX_CHANGES_PER_SYNC - dirtyLists.size)
        val hasMoreToPush = dirtyLists.size < allDirtyLists.size || dirtyItems.size < allDirtyItems.size

        // Surface "syncing…" plus the counts as they stand now (T-47). Recursive retries below
        // re-enter this and re-report, so the innermost outcome is what the UI settles on.
        syncStatus.started(pending = pendingBefore, blocked = blockedCount())

        val request = SyncRequest(
            cursor = sessionState.syncCursor,
            deviceId = serverConfig.deviceId(),
            fullLists = fullLists,
            changes = SyncChanges(lists = dirtyLists.map { it.toDto() }, items = dirtyItems.map { it.toDto() }),
        )

        val response = try {
            apiProvider.get().sync(request)
        } catch (e: UnauthorizedException) {
            syncStatus.stoppedUnauthorized(pending = pendingBefore, blocked = blockedCount())
            return SyncResult.Unauthorized
        } catch (e: ApiException) {
            // Too old for this server (T-240). ErrorInterceptor has already raised the app-wide
            // state; this is only about leaving the queue untouched. It is emphatically NOT a row
            // refusal — the request never reached a row — so it must return before the 422
            // quarantine rules below, which would otherwise be reached if the server ever sent a
            // row_id along with it.
            if (e.httpStatus == 426) {
                syncStatus.stoppedOutdated(pending = pendingBefore, blocked = blockedCount())
                return SyncResult.UpdateRequired
            }
            if (e.code == "full_resync_required") {
                // Our cursor has fallen below the server's gc_horizon, so there is no incremental
                // delta to be had and the mirror has to be re-based on a cursor-0 pull.
                //
                // Re-based, NOT wiped (T-259). This used to call clearAllTables(), on the grounds
                // that the server applies a request's pushed changes before rejecting its cursor,
                // so nothing pushed could be lost. True — but only of the rows in THAT request. A
                // push carries at most MAX_CHANGES_PER_SYNC rows, and dirtyRows() excludes
                // quarantined rows outright, so a week of offline edits past the cap and every row
                // the server refused and the user has not corrected yet were destroyed without a
                // word. So drop exactly the rows the server can reproduce and keep the ones it
                // cannot; deleteSyncedRows() is that complement, and one transaction over both
                // tables leaves no window in which the mirror is half-rebased.
                //
                // What is kept is NOT special-cased afterwards: the cursor-0 pull that follows
                // merges it field by field on the ordinary LWW clocks, exactly like any other pull.
                // A row the server has changed in the meantime therefore wins the fields it
                // touched more recently and loses the ones the local edit touched more recently —
                // including a tombstone, which wins if it is newer. A row the server no longer has
                // at all is simply not mentioned by the pull and stays local, dirty, and pushed on
                // the retry, like any other edit made offline. The retry is not a pure pull any
                // more, which is the point: the backlog goes out with it.
                appDb.inTransaction {
                    listDao.deleteSyncedRows()
                    itemDao.deleteSyncedRows()
                }
                sessionState.syncCursor = 0
                return syncNow(fullLists)
            }
            // One row the server rejected (bad field value) aborts the whole transactional push.
            // Quarantine just that row so it stops wedging the queue, then retry immediately: the
            // remaining dirty rows now go through. The row stays visible/editable; editing it clears
            // the block (ItemsRepo / ListsRepo) so the corrected value is re-tried. Terminates because
            // each retry excludes the blocked row, so the same id can't 422 twice. The getById guards
            // avoid looping if the id names neither a known item nor a known list.
            val badRowId = e.rowId
            // A write the server can NEVER accept as sent is not a bad value to be corrected: the
            // local row now disagrees with a server copy that has older clocks, so no pull would
            // ever overwrite it, and quarantining it would leave that disagreement on screen
            // forever. Worse for these two, the row is invisible — a closed list's item and a
            // tombstoned list are both hidden — so the user can't even edit it to clear the block.
            // Drop the local row instead and ask for a fresh snapshot of its list in the same
            // retry, which restores the server's truth: a write to a closed expenses list (T-157),
            // and a tombstone on an expenses list, which can never be deleted (T-198).
            if (e.httpStatus == 422 && badRowId != null &&
                (e.code == "list_closed" || e.code == "cannot_delete_expense_list")
            ) {
                val listId = itemDao.getById(badRowId)?.listId ?: badRowId.takeIf { listDao.getById(it) != null }
                if (listId != null) {
                    if (itemDao.getById(badRowId) != null) itemDao.hardDelete(badRowId) else listDao.hardDelete(badRowId)
                    return syncNow(fullLists + listId)
                }
            }
            if (e.httpStatus == 422 && badRowId != null) {
                // A list row is parked exactly like an item row (T-198). Before that it fell
                // through to failed() below, so one refused list edit — a rename queued before its
                // author voted to close the list, say — wedged every later sync until the server
                // state changed. The Wire Contract answers 422-with-row_id precisely so the device
                // parks the row instead.
                if (itemDao.getById(badRowId) != null) {
                    // The refusal rides along with the quarantine (T-200): the row is the only place
                    // that can later say why it was parked, and a push queue empties while nobody
                    // is looking — by the time anyone sees it, this exception is long gone.
                    itemDao.blockRow(badRowId, e.code, e.accountId)
                    return syncNow(fullLists)
                }
                if (listDao.getById(badRowId) != null) {
                    listDao.blockRow(badRowId)
                    return syncNow(fullLists)
                }
            }
            val message = e.message ?: "sync failed"
            syncStatus.failed(message, pending = pendingBefore, blocked = blockedCount())
            return SyncResult.Failed(message)
        } catch (e: SSLException) {
            // Distinct, actionable message for an untrusted cert (T-38); SSLException extends
            // IOException, so this catch must precede it.
            //
            // Deliberately NOT a string resource: nothing renders it. SyncStatus.lastError is only
            // ever null-checked (SyncStatusBar picks a colour from it), and SyncWorker discards
            // SyncResult.Failed's message entirely. It is a diagnostic, so translating it would be
            // work with no user-visible effect (T-111).
            val message = "Server certificate not trusted"
            syncStatus.failed(message, pending = pendingBefore, blocked = blockedCount())
            return SyncResult.Failed(message)
        } catch (e: IOException) {
            val message = e.message ?: "network error"
            syncStatus.failed(message, pending = pendingBefore, blocked = blockedCount())
            return SyncResult.Failed(message)
        }

        // Each row's read-merge-write is one transaction (T-261). It has to be: the merge reads the
        // local row, folds the remote clocks into it and writes the WHOLE row back, so a user edit
        // landing in that window is overwritten — and overwritten with the pre-edit clocks, so the
        // row isn't even left dirty and nothing is queued to recover it. The list screen syncs
        // every 5 s while it is open, so the window is hit in ordinary use: check an item off at
        // the wrong moment and it flips back to todo, silently. One transaction per row rather than
        // one for the whole pull: the merge holds SQLite's single write lock for its duration, and
        // a several-hundred-row pull would stall every edit on the device until it finished. The
        // network round trip is already over by here — no transaction ever spans a request.
        for (dto in response.changes.lists) {
            appDb.inTransaction { listDao.upsert(mergeList(listDao.getById(dto.id), dto)) }
        }
        for (dto in response.changes.items) {
            appDb.inTransaction { itemDao.upsert(mergeItem(itemDao.getById(dto.id), dto)) }
        }

        sessionState.syncCursor = response.cursor

        ensureAccountId()
        reportCollaboratorChanges(requestCursor = request.cursor, pulledItems = response.changes.items)

        // Recompute pending after the merge: a local edit that raced the request may still be dirty.
        val pendingAfter = itemDao.dirtyRows().size + listDao.dirtyRows().size

        // More backlog than one batch could carry: go round again (T-114). Guarded on the backlog
        // having actually SHRUNK, not merely on rows remaining — a pushed row is only marked clean
        // when the server echoes it back, and an entirely-stale push it had no reason to bump
        // wouldn't come back at all. Without the guard that row would spin here forever; with it,
        // pending strictly decreases each pass and the recursion is bounded by the backlog size.
        // fullLists is deliberately not repeated: it asks for a snapshot, this pass already took it.
        if (hasMoreToPush && pendingAfter < pendingBefore) {
            return when (val rest = syncNow()) {
                is SyncResult.Success -> SyncResult.Success(
                    pushedItems = dirtyItems.size + rest.pushedItems,
                    pushedLists = dirtyLists.size + rest.pushedLists,
                    pulledItems = response.changes.items.size + rest.pulledItems,
                    pulledLists = response.changes.lists.size + rest.pulledLists,
                )
                else -> rest
            }
        }

        syncStatus.succeeded(
            at = System.currentTimeMillis(),
            pending = pendingAfter,
            blocked = blockedCount(),
        )
        return SyncResult.Success(
            pushedItems = dirtyItems.size,
            pushedLists = dirtyLists.size,
            pulledItems = response.changes.items.size,
            pulledLists = response.changes.lists.size,
        )
    }

    /** Rows the server quarantined with a 422, items and lists alike (T-32, T-198). */
    private suspend fun blockedCount(): Int = itemDao.blockedRowCount() + listDao.blockedRowCount()

    /**
     * Self-heals a missing account id (T-74). accountId is only stored at login, so a session that
     * predates that (pre-v1.2.0) has it null — and [reportCollaboratorChanges] would then silently
     * never fire. There's no /me endpoint, but any list's members roster carries account_id + email
     * and includes the current user, so match our own email to recover it. Best-effort and one-shot
     * per install (stops once set); runs in the background worker too, so it heals without a screen
     * open. Needs a local list to exist (true from the second sync on; the first is cursor-0 anyway).
     */
    private suspend fun ensureAccountId() {
        if (sessionState.accountId != null) return
        val email = sessionState.accountEmail ?: return
        val listId = listDao.anyActiveListId() ?: return
        try {
            val members = apiProvider.get().members(listId).members
            members.firstOrNull { it.email == email }?.let { sessionState.accountId = it.accountId }
        } catch (e: ApiException) {
            // Best-effort — retried on the next sync.
        } catch (e: IOException) {
            // Best-effort — retried on the next sync.
        }
    }

    /**
     * Detects rows in this pull that were last touched by a DIFFERENT account and reports them
     * (T-65). Account-scoped, never device-scoped: a user's own second device must not
     * self-notify. Deliberately silent when: this pass started from cursor 0 (initial hydration /
     * full resync — everything would look "new"), our own account id is unknown (pre-T-65
     * session — can't distinguish, so don't guess), or a row's last_touched_by is null (pre-T-64
     * row never re-touched). Reports RAW detections; pref filtering lives in the notifier impl.
     */
    private suspend fun reportCollaboratorChanges(requestCursor: Long, pulledItems: List<ItemDto>) {
        if (requestCursor == 0L) return
        val myAccountId = sessionState.accountId ?: return
        val foreign = pulledItems.filter { it.lastTouchedBy != null && it.lastTouchedBy != myAccountId }
        if (foreign.isEmpty()) return
        val changes = foreign.groupBy { it.listId }.map { (listId, items) ->
            CollaboratorChange(
                listId = listId,
                // Resolved AFTER the merge loops, so a list first seen in this same pull is found.
                listName = listDao.getById(listId)?.name?.value ?: "a shared list",
                changedItemCount = items.size,
            )
        }
        notifier.notifyCollaboratorChanges(changes)
    }
}

private fun ItemEntity.toDto(): ItemDto = ItemDto(
    id = id,
    listId = listId,
    createdAt = createdAt,
    fields = ItemFieldsDto(
        name = FieldClock(name.value, name.updatedAt, name.updatedBy),
        category = FieldClock(category.value, category.updatedAt, category.updatedBy),
        stores = FieldClock(Json.decodeFromString(stores.value), stores.updatedAt, stores.updatedBy),
        quantity = FieldClock(quantity.value, quantity.updatedAt, quantity.updatedBy),
        price = FieldClock(price.value?.let { Json.decodeFromString<PriceDto>(it) }, price.updatedAt, price.updatedBy),
        note = FieldClock(note.value, note.updatedAt, note.updatedBy),
        status = FieldClock(status.value, status.updatedAt, status.updatedBy),
        // The stored expense is decoded with the lenient AppJson, as everywhere else (T-205): with
        // Json.Default an unknown key would throw here and take the whole push down with it.
        expense = FieldClock(expense.value?.let { AppJson.decodeFromString<Expense>(it) }, expense.updatedAt, expense.updatedBy),
        deleted = FieldClock(deleted.value, deleted.updatedAt, deleted.updatedBy),
    ),
)

private fun ListEntity.toDto(): ListDto = ListDto(
    id = id,
    createdAt = createdAt,
    fields = ListFieldsDto(
        name = FieldClock(name.value, name.updatedAt, name.updatedBy),
        categoryOrder = FieldClock(Json.decodeFromString(categoryOrder.value), categoryOrder.updatedAt, categoryOrder.updatedBy),
        notes = FieldClock(notes.value, notes.updatedAt, notes.updatedBy),
        kind = FieldClock(kind.value, kind.updatedAt, kind.updatedBy),
        currency = FieldClock(currency.value, currency.updatedAt, currency.updatedBy),
        deleted = FieldClock(deleted.value, deleted.updatedAt, deleted.updatedBy),
    ),
)

/** Result of merging one field: the winning value/clock, and whether the LOCAL side won (still unsynced). */
private data class MergedField<T>(val value: T, val updatedAt: Long, val updatedBy: String, val dirty: Boolean)

/** Local wins only if strictly newer (tuple-compare, updatedBy tiebreak) — ties/remote-newer both clear dirty. */
private fun <T> mergeField(localValue: T, localAt: Long, localBy: String, remote: FieldClock<T>): MergedField<T> {
    val localNewer = if (localAt != remote.updatedAt) localAt > remote.updatedAt else localBy > remote.updatedBy
    return if (localNewer) {
        MergedField(localValue, localAt, localBy, dirty = true)
    } else {
        MergedField(remote.value, remote.updatedAt, remote.updatedBy, dirty = false)
    }
}

private fun mergeItem(local: ItemEntity?, remote: ItemDto): ItemEntity {
    val storesRemote = FieldClock(
        Json.encodeToString(remote.fields.stores.value),
        remote.fields.stores.updatedAt,
        remote.fields.stores.updatedBy,
    )
    val priceRemote = FieldClock(
        remote.fields.price.value?.let { Json.encodeToString(it) },
        remote.fields.price.updatedAt,
        remote.fields.price.updatedBy,
    )
    val expenseRemote = FieldClock(
        remote.fields.expense.value?.let { Json.encodeToString(it) },
        remote.fields.expense.updatedAt,
        remote.fields.expense.updatedBy,
    )

    if (local == null) {
        return ItemEntity(
            id = remote.id,
            listId = remote.listId,
            createdAt = remote.createdAt,
            name = remote.fields.name.value.toLww(remote.fields.name.updatedBy, remote.fields.name.updatedAt),
            category = remote.fields.category.value.toLwwOptional(remote.fields.category.updatedBy, remote.fields.category.updatedAt),
            stores = storesRemote.value.toLww(storesRemote.updatedBy, storesRemote.updatedAt),
            quantity = remote.fields.quantity.value.toLwwOptional(remote.fields.quantity.updatedBy, remote.fields.quantity.updatedAt),
            price = priceRemote.value.toLwwOptional(priceRemote.updatedBy, priceRemote.updatedAt),
            note = remote.fields.note.value.toLwwOptional(remote.fields.note.updatedBy, remote.fields.note.updatedAt),
            status = remote.fields.status.value.toLww(remote.fields.status.updatedBy, remote.fields.status.updatedAt),
            expense = expenseRemote.value.toLwwOptional(expenseRemote.updatedBy, expenseRemote.updatedAt),
            deleted = remote.fields.deleted.value.toLww(remote.fields.deleted.updatedBy, remote.fields.deleted.updatedAt),
            dirty = false,
            lastTouchedByAccountId = remote.lastTouchedBy,
        )
    }

    val name = mergeField(local.name.value, local.name.updatedAt, local.name.updatedBy, remote.fields.name)
    val category = mergeField(local.category.value, local.category.updatedAt, local.category.updatedBy, remote.fields.category)
    val stores = mergeField(local.stores.value, local.stores.updatedAt, local.stores.updatedBy, storesRemote)
    val quantity = mergeField(local.quantity.value, local.quantity.updatedAt, local.quantity.updatedBy, remote.fields.quantity)
    val price = mergeField(local.price.value, local.price.updatedAt, local.price.updatedBy, priceRemote)
    val note = mergeField(local.note.value, local.note.updatedAt, local.note.updatedBy, remote.fields.note)
    val status = mergeField(local.status.value, local.status.updatedAt, local.status.updatedBy, remote.fields.status)
    val expense = mergeField(local.expense.value, local.expense.updatedAt, local.expense.updatedBy, expenseRemote)
    val deleted = mergeField(local.deleted.value, local.deleted.updatedAt, local.deleted.updatedBy, remote.fields.deleted)
    val mergedDirty = name.dirty || category.dirty || stores.dirty || quantity.dirty || price.dirty ||
        note.dirty || status.dirty || expense.dirty || deleted.dirty
    val stillBlocked = local.syncBlocked && mergedDirty

    return ItemEntity(
        id = local.id,
        listId = local.listId,
        createdAt = local.createdAt,
        name = LwwString(name.value, name.updatedAt, name.updatedBy),
        category = LwwOptionalString(category.value, category.updatedAt, category.updatedBy),
        stores = LwwString(stores.value, stores.updatedAt, stores.updatedBy),
        quantity = LwwOptionalString(quantity.value, quantity.updatedAt, quantity.updatedBy),
        price = LwwOptionalString(price.value, price.updatedAt, price.updatedBy),
        note = LwwOptionalString(note.value, note.updatedAt, note.updatedBy),
        status = LwwString(status.value, status.updatedAt, status.updatedBy),
        expense = LwwOptionalString(expense.value, expense.updatedAt, expense.updatedBy),
        deleted = LwwBoolean(deleted.value, deleted.updatedAt, deleted.updatedBy),
        dirty = mergedDirty,
        // A quarantined row stays quarantined only while it still has unpushed local state; once a
        // merge leaves nothing dirty (remote fully superseded the local edits) the block is moot.
        // A user edit clears it regardless (ItemsRepo). syncBlocked isn't a synced field, so it's
        // taken from the local row, never the remote DTO. The refusal goes with it (T-200): it
        // describes a value that is no longer what the row holds.
        syncBlocked = stillBlocked,
        syncBlockedCode = local.syncBlockedCode.takeIf { stillBlocked },
        syncBlockedAccountId = local.syncBlockedAccountId.takeIf { stillBlocked },
        // Not an LWW field (T-64) — always mirrors the server's latest report, unconditionally.
        lastTouchedByAccountId = remote.lastTouchedBy,
    )
}

private fun mergeList(local: ListEntity?, remote: ListDto): ListEntity {
    val categoryOrderRemote = FieldClock(
        Json.encodeToString(remote.fields.categoryOrder.value),
        remote.fields.categoryOrder.updatedAt,
        remote.fields.categoryOrder.updatedBy,
    )

    if (local == null) {
        return ListEntity(
            id = remote.id,
            createdAt = remote.createdAt,
            name = remote.fields.name.value.toLww(remote.fields.name.updatedBy, remote.fields.name.updatedAt),
            categoryOrder = categoryOrderRemote.value.toLww(categoryOrderRemote.updatedBy, categoryOrderRemote.updatedAt),
            notes = remote.fields.notes.value.toLwwOptional(remote.fields.notes.updatedBy, remote.fields.notes.updatedAt),
            kind = remote.fields.kind.value.toLww(remote.fields.kind.updatedBy, remote.fields.kind.updatedAt),
            currency = remote.fields.currency.value.toLwwOptional(remote.fields.currency.updatedBy, remote.fields.currency.updatedAt),
            deleted = remote.fields.deleted.value.toLww(remote.fields.deleted.updatedBy, remote.fields.deleted.updatedAt),
            dirty = false,
            membersJson = Json.encodeToString(remote.members),
            closeVotesJson = Json.encodeToString(remote.closeVotes),
            closedAt = remote.closedAt,
        )
    }

    val name = mergeField(local.name.value, local.name.updatedAt, local.name.updatedBy, remote.fields.name)
    val categoryOrder = mergeField(local.categoryOrder.value, local.categoryOrder.updatedAt, local.categoryOrder.updatedBy, categoryOrderRemote)
    val notes = mergeField(local.notes.value, local.notes.updatedAt, local.notes.updatedBy, remote.fields.notes)
    val kind = mergeField(local.kind.value, local.kind.updatedAt, local.kind.updatedBy, remote.fields.kind)
    val currency = mergeField(local.currency.value, local.currency.updatedAt, local.currency.updatedBy, remote.fields.currency)
    val deleted = mergeField(local.deleted.value, local.deleted.updatedAt, local.deleted.updatedBy, remote.fields.deleted)
    val mergedDirty = name.dirty || categoryOrder.dirty || notes.dirty || kind.dirty || currency.dirty || deleted.dirty

    return ListEntity(
        id = local.id,
        createdAt = local.createdAt,
        name = LwwString(name.value, name.updatedAt, name.updatedBy),
        categoryOrder = LwwString(categoryOrder.value, categoryOrder.updatedAt, categoryOrder.updatedBy),
        notes = LwwOptionalString(notes.value, notes.updatedAt, notes.updatedBy),
        kind = LwwString(kind.value, kind.updatedAt, kind.updatedBy),
        currency = LwwOptionalString(currency.value, currency.updatedAt, currency.updatedBy),
        deleted = LwwBoolean(deleted.value, deleted.updatedAt, deleted.updatedBy),
        dirty = mergedDirty,
        // Quarantined only while there is still unpushed local state, as for an item (T-198).
        syncBlocked = local.syncBlocked && mergedDirty,
        // Not LWW (T-152): always whatever the server last said, like an item's lastTouchedBy.
        membersJson = Json.encodeToString(remote.members),
        closeVotesJson = Json.encodeToString(remote.closeVotes),
        closedAt = remote.closedAt,
    )
}
