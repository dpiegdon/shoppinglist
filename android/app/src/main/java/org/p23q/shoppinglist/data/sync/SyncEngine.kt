package org.p23q.shoppinglist.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.data.ServerConfig
import org.p23q.shoppinglist.data.SessionState
import org.p23q.shoppinglist.data.api.ApiException
import org.p23q.shoppinglist.data.api.ApiProvider
import org.p23q.shoppinglist.data.api.FieldClock
import org.p23q.shoppinglist.data.api.ItemDto
import org.p23q.shoppinglist.data.api.ItemFieldsDto
import org.p23q.shoppinglist.data.api.ListDto
import org.p23q.shoppinglist.data.api.ListFieldsDto
import org.p23q.shoppinglist.data.api.PriceDto
import org.p23q.shoppinglist.data.api.SyncChanges
import org.p23q.shoppinglist.data.api.SyncRequest
import org.p23q.shoppinglist.data.api.UnauthorizedException
import org.p23q.shoppinglist.data.db.AppDb
import org.p23q.shoppinglist.data.db.ItemDao
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.ListDao
import org.p23q.shoppinglist.data.db.ListEntity
import org.p23q.shoppinglist.data.db.LwwBoolean
import org.p23q.shoppinglist.data.db.LwwOptionalString
import org.p23q.shoppinglist.data.db.LwwString
import org.p23q.shoppinglist.data.db.toLww
import org.p23q.shoppinglist.data.db.toLwwOptional
import java.io.IOException
import javax.inject.Inject
import javax.net.ssl.SSLException

sealed interface SyncResult {
    data class Success(val pushedItems: Int, val pushedLists: Int, val pulledItems: Int, val pulledLists: Int) : SyncResult
    data object Unauthorized : SyncResult
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
) {
    suspend fun syncNow(fullLists: List<String> = emptyList()): SyncResult {
        val dirtyItems = itemDao.dirtyRows()
        val dirtyLists = listDao.dirtyRows()
        val pendingBefore = dirtyItems.size + dirtyLists.size
        // Surface "syncing…" plus the counts as they stand now (T-47). Recursive retries below
        // re-enter this and re-report, so the innermost outcome is what the UI settles on.
        syncStatus.started(pending = pendingBefore, blocked = itemDao.blockedRowCount())

        val request = SyncRequest(
            cursor = sessionState.syncCursor,
            deviceId = serverConfig.deviceId(),
            fullLists = fullLists,
            changes = SyncChanges(lists = dirtyLists.map { it.toDto() }, items = dirtyItems.map { it.toDto() }),
        )

        val response = try {
            apiProvider.get().sync(request)
        } catch (e: UnauthorizedException) {
            syncStatus.stoppedUnauthorized(pending = pendingBefore, blocked = itemDao.blockedRowCount())
            return SyncResult.Unauthorized
        } catch (e: ApiException) {
            if (e.code == "full_resync_required") {
                // The server still applied our pushed changes before rejecting the cursor (Wire
                // Contract), so it's safe to wipe: nothing pushed is lost, a fresh cursor-0 pull
                // brings it all back. dirtyRows() will be empty post-wipe, so the retry is a pure pull.
                withContext(Dispatchers.IO) { appDb.clearAllTables() }
                sessionState.syncCursor = 0
                return syncNow(fullLists)
            }
            // One row the server rejected (bad field value) aborts the whole transactional push.
            // Quarantine just that row so it stops wedging the queue, then retry immediately: the
            // remaining dirty rows now go through. The row stays visible/editable; editing it clears
            // the block (ItemsRepo) so the corrected value is re-tried. Terminates because each retry
            // excludes the blocked row, so the same id can't 422 twice. The getById guard avoids
            // looping if the id isn't a known item (e.g. a list row we don't quarantine).
            val badRowId = e.rowId
            if (e.httpStatus == 422 && badRowId != null && itemDao.getById(badRowId) != null) {
                itemDao.blockRow(badRowId)
                return syncNow(fullLists)
            }
            val message = e.message ?: "sync failed"
            syncStatus.failed(message, pending = pendingBefore, blocked = itemDao.blockedRowCount())
            return SyncResult.Failed(message)
        } catch (e: SSLException) {
            // Distinct, actionable message for an untrusted cert (T-38); SSLException extends
            // IOException, so this catch must precede it.
            val message = "Server certificate not trusted"
            syncStatus.failed(message, pending = pendingBefore, blocked = itemDao.blockedRowCount())
            return SyncResult.Failed(message)
        } catch (e: IOException) {
            val message = e.message ?: "network error"
            syncStatus.failed(message, pending = pendingBefore, blocked = itemDao.blockedRowCount())
            return SyncResult.Failed(message)
        }

        for (dto in response.changes.lists) {
            listDao.upsert(mergeList(listDao.getById(dto.id), dto))
        }
        for (dto in response.changes.items) {
            itemDao.upsert(mergeItem(itemDao.getById(dto.id), dto))
        }

        sessionState.syncCursor = response.cursor

        ensureAccountId()
        reportCollaboratorChanges(requestCursor = request.cursor, pulledItems = response.changes.items)

        // Recompute pending after the merge: a local edit that raced the request may still be dirty.
        syncStatus.succeeded(
            at = System.currentTimeMillis(),
            pending = itemDao.dirtyRows().size + listDao.dirtyRows().size,
            blocked = itemDao.blockedRowCount(),
        )
        return SyncResult.Success(
            pushedItems = dirtyItems.size,
            pushedLists = dirtyLists.size,
            pulledItems = response.changes.items.size,
            pulledLists = response.changes.lists.size,
        )
    }

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
    val deleted = mergeField(local.deleted.value, local.deleted.updatedAt, local.deleted.updatedBy, remote.fields.deleted)
    val mergedDirty = name.dirty || category.dirty || stores.dirty || quantity.dirty || price.dirty ||
        note.dirty || status.dirty || deleted.dirty

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
        deleted = LwwBoolean(deleted.value, deleted.updatedAt, deleted.updatedBy),
        dirty = mergedDirty,
        // A quarantined row stays quarantined only while it still has unpushed local state; once a
        // merge leaves nothing dirty (remote fully superseded the local edits) the block is moot.
        // A user edit clears it regardless (ItemsRepo). syncBlocked isn't a synced field, so it's
        // taken from the local row, never the remote DTO.
        syncBlocked = local.syncBlocked && mergedDirty,
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
            deleted = remote.fields.deleted.value.toLww(remote.fields.deleted.updatedBy, remote.fields.deleted.updatedAt),
            dirty = false,
        )
    }

    val name = mergeField(local.name.value, local.name.updatedAt, local.name.updatedBy, remote.fields.name)
    val categoryOrder = mergeField(local.categoryOrder.value, local.categoryOrder.updatedAt, local.categoryOrder.updatedBy, categoryOrderRemote)
    val notes = mergeField(local.notes.value, local.notes.updatedAt, local.notes.updatedBy, remote.fields.notes)
    val deleted = mergeField(local.deleted.value, local.deleted.updatedAt, local.deleted.updatedBy, remote.fields.deleted)

    return ListEntity(
        id = local.id,
        createdAt = local.createdAt,
        name = LwwString(name.value, name.updatedAt, name.updatedBy),
        categoryOrder = LwwString(categoryOrder.value, categoryOrder.updatedAt, categoryOrder.updatedBy),
        notes = LwwOptionalString(notes.value, notes.updatedAt, notes.updatedBy),
        deleted = LwwBoolean(deleted.value, deleted.updatedAt, deleted.updatedBy),
        dirty = name.dirty || categoryOrder.dirty || notes.dirty || deleted.dirty,
    )
}
