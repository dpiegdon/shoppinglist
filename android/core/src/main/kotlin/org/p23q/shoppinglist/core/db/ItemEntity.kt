package org.p23q.shoppinglist.core.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.p23q.shoppinglist.core.db.LwwBoolean
import org.p23q.shoppinglist.core.db.LwwOptionalString
import org.p23q.shoppinglist.core.db.LwwString

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
    /**
     * JSON-encoded [org.p23q.shoppinglist.core.Expense], or null (T-151) — text rather than a
     * decoded type for the same reason as [stores] and [price]: the whole object is one LWW field.
     * Non-null exactly on the items of an expenses list.
     */
    @Embedded(prefix = "expense_") val expense: LwwOptionalString = LwwOptionalString(null, 0, ""),
    @Embedded(prefix = "deleted_") val deleted: LwwBoolean,
    val dirty: Boolean,
    /**
     * Set when the server rejected this row's pushed value with a 422 (T-32). The row stays in the
     * mirror (visible + editable) but [ItemDao.dirtyRows] skips it, so one bad field can't wedge
     * the whole push queue. Cleared the moment the user edits the row again (see ItemsRepo).
     */
    val syncBlocked: Boolean = false,
    /**
     * Why the server refused it, kept with the quarantine (T-200): the 422's error code, and the
     * `account_id` it named when it carries one (participant_frozen does). Without them the only
     * signal was a count in the status bar — the row itself could not say what was wrong, and the
     * push queue's whole point is that the user is not watching when it happens. Null unless
     * [syncBlocked]; cleared with it.
     */
    val syncBlockedCode: String? = null,
    val syncBlockedAccountId: String? = null,
    /**
     * Whole-item, account-scoped (T-64) — NOT an LWW clock like the fields above; the client never
     * sets this locally, it's simply whatever the server last reported. Null until synced at least
     * once after this column existed server-side.
     */
    val lastTouchedByAccountId: String? = null,
)

/**
 * Everything a quarantine left on a row, cleared together (T-32, T-200). Any user edit means the
 * corrected row is retried, and the refusal that parked it no longer describes what is stored.
 */
fun ItemEntity.unblocked(): ItemEntity =
    copy(syncBlocked = false, syncBlockedCode = null, syncBlockedAccountId = null)
