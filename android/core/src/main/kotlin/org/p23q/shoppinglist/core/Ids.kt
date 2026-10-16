package org.p23q.shoppinglist.core

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A fresh random id, a version 4 UUID in its usual lower-case form: a local or server id this device mints. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()
