package org.p23q.shoppinglist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-111. These deliberately mirror the web client's `src/i18n/i18n.test.ts`: the two locale tables
 * must agree tag-for-tag, and the surest way to keep them agreeing is to assert the same behaviour
 * on both sides.
 */
class AppLocaleTest {

    @Test
    fun `matches an exact tag`() {
        assertEquals(AppLocale.GERMAN, AppLocale.match("de"))
        assertEquals(AppLocale.PORTUGUESE_BR, AppLocale.match("pt-BR"))
    }

    @Test
    fun `is case-insensitive, because platforms are inconsistent about tag casing`() {
        assertEquals(AppLocale.PORTUGUESE_BR, AppLocale.match("PT-br"))
        assertEquals(AppLocale.CHINESE_SIMPLIFIED, AppLocale.match("ZH-hans"))
    }

    @Test
    fun `falls back from a region we do not ship to the same base language`() {
        // Without this a Portuguese device would land on English purely because we ship the
        // Brazilian variant, which is a far worse answer than pt-BR.
        assertEquals(AppLocale.PORTUGUESE_BR, AppLocale.match("pt-PT"))
        assertEquals(AppLocale.GERMAN, AppLocale.match("de-AT"))
        assertEquals(AppLocale.ARABIC, AppLocale.match("ar-EG"))
    }

    @Test
    fun `resolves a bare base tag to the variant we ship`() {
        assertEquals(AppLocale.CHINESE_SIMPLIFIED, AppLocale.match("zh"))
        assertEquals(AppLocale.PORTUGUESE_BR, AppLocale.match("pt"))
    }

    @Test
    fun `returns null for a language we do not ship`() {
        assertNull(AppLocale.match("is"))
        assertNull(AppLocale.match(""))
        assertNull(AppLocale.match("   "))
        assertNull(AppLocale.match(null))
    }

    @Test
    fun `takes the first supported preference, in order`() {
        assertEquals(AppLocale.UKRAINIAN, AppLocale.resolve(listOf("is", "fi", "uk", "de")))
        assertEquals(AppLocale.GERMAN, AppLocale.resolve(listOf("de-CH", "en")))
    }

    @Test
    fun `falls back to English when nothing is supported, rather than throwing`() {
        assertEquals(AppLocale.DEFAULT, AppLocale.resolve(listOf("is", "fi")))
        assertEquals(AppLocale.DEFAULT, AppLocale.resolve(emptyList()))
    }

    @Test
    fun `marks Arabic RTL and everything else LTR`() {
        assertTrue(AppLocale.ARABIC.isRtl)
        AppLocale.entries.filter { it != AppLocale.ARABIC }.forEach { assertFalse(it.name, it.isRtl) }
    }

    @Test
    fun `names every language in its own language`() {
        // A user who has accidentally selected a script they cannot read must still be able to
        // find their way back, so these are never translated into the current UI language.
        assertEquals("Deutsch", AppLocale.GERMAN.displayName)
        assertEquals("العربية", AppLocale.ARABIC.displayName)
        assertEquals("Українська", AppLocale.UKRAINIAN.displayName)
    }

    @Test
    fun `has no duplicate tags`() {
        val tags = AppLocale.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun `every tag is a parseable BCP-47 language tag`() {
        // toJavaLocale feeds Configuration.setLocale; a tag Java cannot parse would silently
        // resolve to the root locale and show English while claiming to be something else.
        AppLocale.entries.forEach { locale ->
            assertTrue(locale.tag, locale.toJavaLocale().language.isNotEmpty())
        }
    }
}
