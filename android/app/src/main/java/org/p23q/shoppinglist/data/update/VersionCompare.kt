package org.p23q.shoppinglist.data.update

/**
 * Orders two dot-separated numeric versions ("1.9.3", "1.12.0") the way SemVer means them, not
 * the way string comparison does: "1.9.3" > "1.10.0" lexicographically, and that is exactly the
 * transition this project shipped between 1.9.3 and 1.10.0 (T-135).
 *
 * Returns a negative number if [a] precedes [b], 0 if they are equal, positive if [a] follows
 * [b] — or **null** when either side isn't a plain numeric version. Null rather than an
 * exception: the input is whatever a server said, and an unparseable answer means "I can't
 * tell", which callers must treat as "no update" rather than crash on.
 *
 * Missing trailing fields count as zero, so "1.12" and "1.12.0" are equal.
 */
fun compareVersions(a: String, b: String): Int? {
    val left = parseVersion(a) ?: return null
    val right = parseVersion(b) ?: return null
    for (i in 0 until maxOf(left.size, right.size)) {
        val difference = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (difference != 0) return difference
    }
    return 0
}

/**
 * Digits and dots only. Deliberately strict: a pre-release suffix ("1.2.0-rc1") has an ordering
 * this project has never needed and would be guesswork to invent, so it reads as unparseable —
 * which surfaces as "no update offered", the safe direction to be wrong in.
 */
private fun parseVersion(raw: String): List<Int>? {
    val parts = raw.trim().split('.')
    return parts.map { part ->
        if (part.isEmpty() || !part.all(Char::isDigit)) return null
        part.toIntOrNull() ?: return null
    }
}
