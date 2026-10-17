package org.p23q.shoppinglist.core

/**
 * The languages this client ships, and how a device locale is narrowed to one (T-111).
 *
 * Deliberately mirrors the web client's `web/src/i18n/locales.ts` — same tags, same display names,
 * same three-pass matching — so the two clients can never disagree about which languages exist or
 * what a given device locale resolves to.
 *
 * [displayName] is written in the language's OWN language, never translated into the current UI
 * language. That is the standard convention, and it is the only way a user who has accidentally
 * selected a script they cannot read can find their way back out. [EMOJI] alone also carries its name
 * in the current UI language (the picker's job), since "😀🔤" alone does not say what it is.
 *
 * [formatTag] is the language numbers, amounts and dates are written in: the language's own,
 * except for [EMOJI], which has no conventions of its own and borrows English's.
 */
enum class AppLocale(val tag: String, val displayName: String, val formatTag: String = tag) {
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    PORTUGUESE_BR("pt-BR", "Português (Brasil)"),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    JAPANESE("ja", "日本語"),
    UKRAINIAN("uk", "Українська"),
    ARABIC("ar", "العربية"),

    /**
     * Every string in emoji (T-359), for fun. Its tag is from ISO 639's local-use range, so no real
     * device locale ever resolves to it: it is only ever chosen in the picker, where it is last and
     * carries "(Emoji)" in the current language. Numbers, amounts and dates are English.
     */
    EMOJI("qaa", "😀🔤", formatTag = "en"),
    ;

    /**
     * True for right-to-left scripts. Held as a property rather than an `== ARABIC` test at each
     * call site so adding Hebrew or Farsi later touches this file only (T-126).
     */
    val isRtl: Boolean get() = this == ARABIC

    companion object {
        val DEFAULT = ENGLISH

        /**
         * Narrows one BCP-47 tag to a shipped language, or null if nothing matches.
         *
         * Two passes, most specific first: exact ("pt-BR"), then the same base language with any
         * region ("pt-PT" -> pt-BR, "de-AT" -> de, "zh" -> zh-Hans). The second pass is what stops
         * a Portuguese device landing on English purely because we ship the Brazilian variant, and
         * what makes a bare "zh" resolve at all.
         */
        fun match(tag: String?): AppLocale? {
            val wanted = tag?.trim()?.lowercase().orEmpty()
            if (wanted.isEmpty()) return null
            entries.firstOrNull { it.tag.lowercase() == wanted }?.let { return it }
            val base = wanted.substringBefore('-')
            return entries.firstOrNull { it.tag.lowercase().substringBefore('-') == base }
        }

        /**
         * The best shipped language for an ordered preference list, else [DEFAULT]. Order matters:
         * the platform lists preferences most-wanted first, so the first match wins.
         */
        fun resolve(preferred: List<String>): AppLocale =
            preferred.firstNotNullOfOrNull { match(it) } ?: DEFAULT
    }
}
