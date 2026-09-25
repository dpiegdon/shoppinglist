package org.p23q.shoppinglist.core

/**
 * The invite in pasted text (T-300): a whole message such as "Join my list: https://…/invite/T"
 * is pasted as readily as the link alone, so the first https URL in it, preferring one with an
 * `/invite/` segment, with any sentence punctuation after it dropped. Text with no https URL in it
 * (a bare token) is returned trimmed, unchanged. The web's pastedInvite does the same; both are
 * pinned by shared-test-cases/invite-paste.json.
 */
fun pastedInvite(raw: String): String {
    val urls = HTTPS_URL.findAll(raw).map { it.value.trimEnd(*URL_TRAILING_PUNCTUATION) }.toList()
    return urls.firstOrNull { it.contains("/invite/") } ?: urls.firstOrNull() ?: raw.trim()
}

// A URL runs to the first whitespace. The Unicode spaces are spelled out so the set is exactly the
// one the web's pattern uses: Java's \s has only the ASCII ones, JavaScript's most of the rest.
private val HTTPS_URL = Regex(
    """https://[^\s\u0085   -     　﻿]+""",
    RegexOption.IGNORE_CASE,
)
private val URL_TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '>', '"', '\'')
