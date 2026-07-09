package org.p23q.shoppinglist.data.db

// Room/KSP can't process a generic @Embedded class whose type parameter resolves to a nullable
// type ("Cannot use unbound properties in entities") — so each LWW column shape below is its own
// concrete class instead of one LwwField<T>, even though the three are otherwise identical.

data class LwwString(val value: String, val updatedAt: Long, val updatedBy: String)
data class LwwOptionalString(val value: String?, val updatedAt: Long, val updatedBy: String)
data class LwwBoolean(val value: Boolean, val updatedAt: Long, val updatedBy: String)

fun String.toLww(updatedBy: String, updatedAt: Long = System.currentTimeMillis()): LwwString =
    LwwString(this, updatedAt, updatedBy)

fun String?.toLwwOptional(updatedBy: String, updatedAt: Long = System.currentTimeMillis()): LwwOptionalString =
    LwwOptionalString(this, updatedAt, updatedBy)

fun Boolean.toLww(updatedBy: String, updatedAt: Long = System.currentTimeMillis()): LwwBoolean =
    LwwBoolean(this, updatedAt, updatedBy)
