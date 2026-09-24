package org.p23q.shoppinglist.ui

import android.net.Uri

/**
 * The login destination's optional arguments (T-292), as the overview's sign-in banner and the
 * invite-link routing ask for it: `login?mode=…&accountId=…&serverUrl=…`.
 *
 * - [MODE_RESIGNIN] with [ACCOUNT_ID]: sign that account in again.
 * - [MODE_ADD], optionally with [SERVER_URL]: add another account, the form prefilled with that
 *   server (an invite link for a server this phone has no account on).
 */
object LoginArgs {
    const val MODE = "mode"
    const val ACCOUNT_ID = "accountId"
    const val SERVER_URL = "serverUrl"

    const val MODE_START = "start"
    const val MODE_ADD = "add"
    const val MODE_RESIGNIN = "resignin"
}

/** The login route with [mode] and its arguments; see [LoginArgs]. */
fun Routes.login(mode: String, accountId: String? = null, serverUrl: String? = null): String = buildString {
    append(LOGIN).append('?').append(LoginArgs.MODE).append('=').append(Uri.encode(mode))
    accountId?.let { append('&').append(LoginArgs.ACCOUNT_ID).append('=').append(Uri.encode(it)) }
    serverUrl?.let { append('&').append(LoginArgs.SERVER_URL).append('=').append(Uri.encode(it)) }
}
