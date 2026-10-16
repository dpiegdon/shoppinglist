package org.p23q.shoppinglist.core

import java.text.Normalizer

/** The Unicode the shared name order ([NameOrder]) needs that the Kotlin standard library lacks. */
internal object UnicodeText {
    /** Compatibility decomposition (NFKD). */
    fun nfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)

    /** A combining mark: general category Mn, Mc or Me. */
    fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> false
    }

    /** A letter (general category L) or a decimal digit (Nd). */
    fun isLetterOrDigit(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint)
}
