package org.p23q.shoppinglist.data

import java.text.Normalizer
import java.util.Locale

/**
 * The one name order both clients use (T-176) — lists on the overview, categories, items, the All
 * items screen. Pinned by shared-test-cases/name-order.json, which the web's nameOrder.ts is tested
 * against too; the rules are spelled out there.
 *
 * Not a java.text.Collator (nor SQLite's NOCASE, which folds only A-Z): the web and Android used
 * different platform rules, and put accented, non-Latin and emoji-led names in different places.
 */
object NameOrder {

    private val folds = listOf("ß" to "ss", "æ" to "ae", "œ" to "oe", "ø" to "o", "đ" to "d", "ł" to "l", "þ" to "th")

    private val markTypes = setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
    )

    /** What a name sorts by: no accents, no case, nothing before its first letter or digit. */
    fun key(name: String): String {
        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD)
        val unmarked = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            if (Character.getType(cp) !in markTypes) unmarked.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        var key = unmarked.toString().lowercase(Locale.ROOT)
        for ((from, to) in folds) key = key.replace(from, to)
        var start = 0
        while (start < key.length) {
            val cp = key.codePointAt(start)
            if (Character.isLetterOrDigit(cp)) break
            start += Character.charCount(cp)
        }
        return key.substring(start)
    }

    /** Two names in the shared order; equal keys fall back to the full name. String.compareTo is by
     *  UTF-16 code unit, which is how the web compares them too. */
    val names: Comparator<String> = Comparator { a, b ->
        val byKey = key(a).compareTo(key(b))
        if (byKey != 0) byKey else a.compareTo(b)
    }

    /** A comparator for things with a name and an id, total even when two names are identical. */
    fun <T> by(name: (T) -> String, id: (T) -> String): Comparator<T> = Comparator { a, b ->
        val byName = names.compare(name(a), name(b))
        if (byName != 0) byName else id(a).compareTo(id(b))
    }
}
