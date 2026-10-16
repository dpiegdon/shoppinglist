package org.p23q.shoppinglist.core

import java.text.Normalizer

internal actual object UnicodeText {
    actual fun nfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)

    actual fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> false
    }

    actual fun isLetterOrDigit(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint)

    actual fun isFormat(codePoint: Int): Boolean = Character.getType(codePoint) == Character.FORMAT.toInt()
}
