package org.p23q.shoppinglist.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.p23q.shoppinglist.data.DeviceIdProvider
import org.p23q.shoppinglist.data.db.ListDao
import org.p23q.shoppinglist.data.db.ListEntity
import org.p23q.shoppinglist.data.db.toLww
import org.p23q.shoppinglist.data.sync.SyncTrigger
import java.util.UUID
import javax.inject.Inject

class ListsRepo @Inject constructor(
    private val listDao: ListDao,
    private val deviceId: DeviceIdProvider,
    private val syncTrigger: SyncTrigger,
) {
    fun activeLists(): Flow<List<ListEntity>> = listDao.activeLists()

    suspend fun getById(listId: String): ListEntity? = listDao.getById(listId)

    /** Live single-list observation (T-34) — reflects rename / category-order changes as they land. */
    fun observeById(listId: String): Flow<ListEntity?> = listDao.observeById(listId)

    suspend fun dirtyRows(): List<ListEntity> = listDao.dirtyRows()

    suspend fun clearDirty(ids: List<String>) = listDao.clearDirty(ids)

    suspend fun createList(name: String): String {
        val id = UUID.randomUUID().toString()
        val by = deviceId.get()
        val now = System.currentTimeMillis()
        listDao.upsert(
            ListEntity(
                id = id,
                createdAt = now,
                name = name.toLww(by, now),
                categoryOrder = encodeCategoryOrder(emptyList()).toLww(by, now),
                deleted = false.toLww(by, now),
                dirty = true,
            ),
        )
        syncTrigger.scheduleAfterEdit()
        return id
    }

    suspend fun rename(listId: String, name: String) =
        updateField(listId) { it.copy(name = name.toLww(deviceId.get())) }

    suspend fun setCategoryOrder(listId: String, order: List<String>) =
        updateField(listId) { it.copy(categoryOrder = encodeCategoryOrder(order).toLww(deviceId.get())) }

    /** Tombstone: [ListEntity.deleted] flips true, the row itself is retained for sync. */
    suspend fun delete(listId: String) = updateField(listId) { it.copy(deleted = true.toLww(deviceId.get())) }

    /** Real delete, not the LWW tombstone — only for leaving a shared list (A9), never synced. */
    suspend fun removeLocally(listId: String) = listDao.hardDelete(listId)

    fun decodeCategoryOrder(json: String): List<String> = Json.decodeFromString(json)

    private fun encodeCategoryOrder(order: List<String>): String = Json.encodeToString(order)

    private suspend fun updateField(listId: String, mutate: suspend (ListEntity) -> ListEntity) {
        val current = listDao.getById(listId) ?: return
        listDao.upsert(mutate(current).copy(dirty = true))
        syncTrigger.scheduleAfterEdit()
    }
}
