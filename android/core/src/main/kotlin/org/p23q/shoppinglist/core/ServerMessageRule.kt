package org.p23q.shoppinglist.core

/**
 * The rule for the admin's server message (T-315, T-316), the one the server applies and the web
 * client checks too; `shared-test-cases/server-message.json` pins it for all three.
 *
 * Trim U+0009, U+000A, U+000D, U+0020 and every space separator (Zs) from both ends. Then refuse
 * any control character (Cc), the line and paragraph separators (Zl, Zp), the bidi embeddings and
 * overrides U+202A–U+202E and any unpaired surrogate, and more than [MAX] code points. What is
 * left is the message, unless it holds nothing but format characters (Cf) and spaces (Zs): then
 * there is none. The bidi isolates U+2066–U+2069, ZWJ, ZWNJ, LRM and RLM stay allowed.
 */
object ServerMessageRule {

    const val MAX = 200

    sealed interface Result {
        /** Accepted; [message] is what the server stores, null for no message. */
        data class Valid(val message: String?) : Result

        /** The server would refuse it with `invalid_message`. */
        data object Invalid : Result
    }

    fun check(input: String): Result {
        val trimmed = trim(input)
        val codePoints = trimmed.codePoints().toArray()
        if (codePoints.any(::refused)) return Result.Invalid
        if (codePoints.size > MAX) return Result.Invalid
        val blank = codePoints.all {
            val type = Character.getType(it)
            type == Character.FORMAT.toInt() || type == Character.SPACE_SEPARATOR.toInt()
        }
        return Result.Valid(if (blank) null else trimmed)
    }

    private fun trimmable(c: Char): Boolean =
        c == '\t' || c == '\n' || c == '\r' || c == ' ' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    // Every Zs is in the Basic Multilingual Plane, so trimming by UTF-16 unit is trimming by code point.
    private fun trim(s: String): String = s.trim(::trimmable)

    private fun refused(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.CONTROL.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt() ||
            // codePoints() yields an unpaired surrogate as itself.
            type == Character.SURROGATE.toInt() ||
            cp in 0x202A..0x202E
    }
}
