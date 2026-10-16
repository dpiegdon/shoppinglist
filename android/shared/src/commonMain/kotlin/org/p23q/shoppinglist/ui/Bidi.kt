package org.p23q.shoppinglist.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Wraps a name in Unicode first-strong isolates (FSI … PDI) before it goes into a sentence, so a
 * name written right to left cannot reorder the words and arrows around it: "%1$s → %2$s" with two
 * Arabic names otherwise reads back to front. The web's bidiIsolate does the same.
 */
fun bidiIsolate(text: String): String = "$FIRST_STRONG_ISOLATE$text$POP_DIRECTIONAL_ISOLATE"

// By code point rather than as escapes in a string literal, which lint reads as bidi spoofing.
private val FIRST_STRONG_ISOLATE = Char(0x2068)
private val POP_DIRECTIONAL_ISOLATE = Char(0x2069)

/**
 * The app's own reading direction as a text direction, for a sentence built from isolated names:
 * it must read in the language it is written in, not in whichever script its first name happens to
 * be, which is what Compose's default guesses from. That is why Arabic's arrow points left.
 */
@Composable
fun layoutTextDirection(): TextDirection =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr
