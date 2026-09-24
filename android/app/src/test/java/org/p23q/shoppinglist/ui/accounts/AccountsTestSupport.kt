package org.p23q.shoppinglist.ui.accounts

import org.p23q.shoppinglist.core.db.AccountEntity
import org.p23q.shoppinglist.data.testAccount

/** An account row for the Accounts tests: [id] on [serverUrl], as [email]. */
internal fun accountRow(
    id: String,
    serverUrl: String = "https://lists.example.test/",
    email: String = "$id@example.com",
    signedIn: Boolean = true,
    outdated: Boolean = false,
): AccountEntity = testAccount(id = id, serverUrl = serverUrl, accountId = "acct-$id", email = email, signedIn = signedIn)
    .copy(outdated = outdated)

/** A device-local account (B3's); none can be made yet, but the screen already shows one. */
internal fun localAccountRow(id: String = "local"): AccountEntity = AccountEntity(
    id = id,
    kind = AccountEntity.KIND_LOCAL,
    serverUrl = null,
    accountId = null,
    email = null,
    label = "This phone",
    signedIn = false,
)
