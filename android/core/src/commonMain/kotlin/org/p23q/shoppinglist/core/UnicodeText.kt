package org.p23q.shoppinglist.core

/**
 * The Unicode the shared rules ([NameOrder], [ServerMessageRule]) need that the Kotlin standard
 * library lacks, or has only for the Basic Multilingual Plane. The JVM asks java.text and
 * java.lang.Character, iOS asks Foundation.
 */
internal expect object UnicodeText {
    /** Compatibility decomposition (NFKD). */
    fun nfkd(s: String): String

    /** A combining mark: general category Mn, Mc or Me. */
    fun isMark(codePoint: Int): Boolean

    /** A letter (general category L) or a decimal digit (Nd). */
    fun isLetterOrDigit(codePoint: Int): Boolean

    /** A format character: general category Cf. */
    fun isFormat(codePoint: Int): Boolean
}

/**
 * Calls [action] with each code point of [s] and its UTF-16 range, until it returns false. An
 * unpaired surrogate is a code point of its own, as java.lang.String.codePoints() yields it.
 */
internal inline fun forEachCodePoint(s: String, action: (codePoint: Int, start: Int, end: Int) -> Boolean) {
    var i = 0
    while (i < s.length) {
        val high = s[i]
        val pair = high.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
        val codePoint = if (pair) 0x10000 + ((high.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) else high.code
        val end = if (pair) i + 2 else i + 1
        if (!action(codePoint, i, end)) return
        i = end
    }
}

/** The code points of [s]; see [forEachCodePoint]. */
internal fun codePointsOf(s: String): List<Int> {
    val codePoints = ArrayList<Int>(s.length)
    forEachCodePoint(s) { codePoint, _, _ ->
        codePoints += codePoint
        true
    }
    return codePoints
}
