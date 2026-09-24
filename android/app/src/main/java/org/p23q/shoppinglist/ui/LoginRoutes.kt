package org.p23q.shoppinglist.ui

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
    append(LOGIN).append('?').append(LoginArgs.MODE).append('=').append(routeArg(mode))
    accountId?.let { append('&').append(LoginArgs.ACCOUNT_ID).append('=').append(routeArg(it)) }
    serverUrl?.let { append('&').append(LoginArgs.SERVER_URL).append('=').append(routeArg(it)) }
}

/**
 * [value] percent-encoded for a route argument: every byte outside the URI's unreserved set, so a
 * server URL's ':' and '/' survive as one argument. Plain Kotlin rather than android.net.Uri, so
 * the routes can be built in a plain JVM test.
 */
internal fun routeArg(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt().toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") {
            append(c)
        } else {
            append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
}
