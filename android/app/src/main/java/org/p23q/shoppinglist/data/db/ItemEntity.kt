package org.p23q.shoppinglist.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local mirror of the Wire Contract's item object. Every syncable field is stored as its own
 * value/updatedAt/updatedBy clock so edits can be pushed field-granular. [stores] and [price]
 * hold JSON-encoded text (array / object-or-null) rather than decoded Kotlin types, matching the
 * Wire Contract's "whole array/object = one LWW field" rule.
 */
@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val createdAt: Long,
    @Embedded(prefix = "name_") val name: LwwString,
    @Embedded(prefix = "category_") val category: LwwOptionalString,
    @Embedded(prefix = "stores_") val stores: LwwString,
    @Embedded(prefix = "quantity_") val quantity: LwwOptionalString,
    @Embedded(prefix = "price_") val price: LwwOptionalString,
    @Embedded(prefix = "note_") val note: LwwOptionalString,
    @Embedded(prefix = "status_") val status: LwwString,
    @Embedded(prefix = "deleted_") val deleted: LwwBoolean,
    val dirty: Boolean,
    /**
     * Set when the server rejected this row's pushed value with a 422 (T-32). The row stays in the
     * mirror (visible + editable) but [ItemDao.dirtyRows] skips it, so one bad field can't wedge
     * the whole push queue. Cleared the moment the user edits the row again (see ItemsRepo).
     */
    val syncBlocked: Boolean = false,
)
