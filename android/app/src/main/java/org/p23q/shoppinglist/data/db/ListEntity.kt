package org.p23q.shoppinglist.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local mirror of the Wire Contract's list object; [categoryOrder] holds JSON-encoded array text. */
@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    @Embedded(prefix = "name_") val name: LwwString,
    @Embedded(prefix = "categoryOrder_") val categoryOrder: LwwString,
    @Embedded(prefix = "notes_") val notes: LwwOptionalString,
    @Embedded(prefix = "deleted_") val deleted: LwwBoolean,
    val dirty: Boolean,
)
