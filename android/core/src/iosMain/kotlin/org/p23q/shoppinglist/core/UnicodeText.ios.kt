package org.p23q.shoppinglist.core

import platform.Foundation.NSCharacterSet
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCompatibilityMapping

internal actual object UnicodeText {
    actual fun nfkd(s: String): String = NSString.create(string = s).decomposedStringWithCompatibilityMapping

    // nonBaseCharacterSet is general category M.
    actual fun isMark(codePoint: Int): Boolean = NSCharacterSet.nonBaseCharacterSet.longCharacterIsMember(codePoint.toUInt())

    // letterCharacterSet is L and M; the marks are taken out again.
    actual fun isLetterOrDigit(codePoint: Int): Boolean =
        (NSCharacterSet.letterCharacterSet.longCharacterIsMember(codePoint.toUInt()) && !isMark(codePoint)) ||
            NSCharacterSet.decimalDigitCharacterSet.longCharacterIsMember(codePoint.toUInt())

    // Foundation has no set for Cf alone (controlCharacterSet is Cc and Cf), so outside the Basic
    // Multilingual Plane, where the standard library's Char.category does not reach, the Cf
    // ranges of Unicode 16 are listed here.
    actual fun isFormat(codePoint: Int): Boolean =
        if (codePoint <= 0xFFFF) {
            codePoint.toChar().category == CharCategory.FORMAT
        } else {
            SUPPLEMENTARY_FORMAT.any { codePoint in it }
        }

    private val SUPPLEMENTARY_FORMAT = listOf(
        0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x1343F, 0x1BCA0..0x1BCA3,
        0x1D173..0x1D17A, 0xE0001..0xE0001, 0xE0020..0xE007F,
    )
}
