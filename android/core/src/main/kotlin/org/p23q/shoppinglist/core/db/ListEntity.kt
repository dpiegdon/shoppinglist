package org.p23q.shoppinglist.core.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local mirror of the Wire Contract's list object; [categoryOrder] holds JSON-encoded array text. */
@Entity(
    tableName = "lists",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"])],
    indices = [Index(value = ["accountId", "serverId"], unique = true)],
)
data class ListEntity(
    /**
     * This phone's own id for the row, and the only one screens, routes, notifications and the
     * last-opened list pass around. Never sent: two accounts on this phone that share a list hold
     * one row each, with the same [serverId].
     */
    @PrimaryKey val localId: String,
    /** The list's id on its server (the wire's `id`), unique per [accountId]. */
    val serverId: String,
    /**
     * The local id of the [AccountEntity] this list belongs to. Not a synced field: it says which
     * account on this device holds the list, and a list never moves to another.
     */
    val accountId: String,
    val createdAt: Long,
    @Embedded(prefix = "name_") val name: LwwString,
    @Embedded(prefix = "categoryOrder_") val categoryOrder: LwwString,
    @Embedded(prefix = "notes_") val notes: LwwOptionalString,
    /**
     * "shopping" | "checklist" (T-110) — see [org.p23q.shoppinglist.core.ListKind]. Purely a
     * display toggle: the item schema is identical for both, so a list can be converted at any
     * time and the hidden fields (stores/price/quantity) survive untouched.
     */
    @Embedded(prefix = "kind_") val kind: LwwString,
    /** Free-text currency label; set exactly on an expenses list (T-151). */
    @Embedded(prefix = "currency_") val currency: LwwOptionalString = LwwOptionalString(null, 0, ""),
    @Embedded(prefix = "deleted_") val deleted: LwwBoolean,
    val dirty: Boolean,
    /**
     * Set when the server rejected this row's pushed values with a 422 (T-198), the same
     * quarantine [org.p23q.shoppinglist.core.db.ItemEntity.syncBlocked] gives an item: the list
     * stays visible and editable, but [ListDao.dirtyRows] skips it, so one refused list row can't
     * wedge the whole push queue. Cleared the moment the user edits the list again (see ListsRepo).
     */
    val syncBlocked: Boolean = false,
    /**
     * Server-maintained, NOT LWW clocks (T-152): the roster and the close-vote state as the server
     * last reported them. Stored as JSON so the mirror has them offline, which is what the expense
     * form's defaults and the balances screen need. The client never writes them.
     */
    val membersJson: String = "[]",
    val closeVotesJson: String = "[]",
    val closedAt: Long? = null,
)
