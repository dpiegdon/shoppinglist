package org.p23q.shoppinglist.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.core.AppLocale
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.action_cancel
import org.p23q.shoppinglist.resources.admin_is_admin_suffix
import org.p23q.shoppinglist.resources.admin_session_count
import org.p23q.shoppinglist.resources.expense_agree_count
import org.p23q.shoppinglist.resources.listprops_leave_confirm_body
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** The shared string resources, read in the app's language rather than the process's (T-351). */
@RunWith(RobolectricTestRunner::class)
class StringsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun get(locale: AppLocale, resource: org.jetbrains.compose.resources.StringResource, vararg args: Any) =
        runBlocking { getString(locale.toJavaLocale(), resource, *args) }

    @Test
    fun `every language resolves to its own file, whatever the process default`() {
        // The process default stays the test's English throughout: the language comes from the
        // argument, which is what lets a notification follow the in-app choice.
        assertEquals("Abbrechen", get(AppLocale.GERMAN, Res.string.action_cancel))
        assertEquals("Cancelar", get(AppLocale.PORTUGUESE_BR, Res.string.action_cancel))
        assertEquals("取消", get(AppLocale.CHINESE_SIMPLIFIED, Res.string.action_cancel))
        assertEquals("キャンセル", get(AppLocale.JAPANESE, Res.string.action_cancel))
        assertEquals("Cancel", get(AppLocale.ENGLISH, Res.string.action_cancel))
        assertEquals("✖️", get(AppLocale.EMOJI, Res.string.action_cancel))
        assertEquals("Cancel", runBlocking { getString(Locale.ITALIAN, Res.string.action_cancel) })
    }

    @Test
    fun `looking a language up leaves the process default locale as it was`() {
        val before = Locale.getDefault()

        get(AppLocale.ARABIC, Res.string.action_cancel)

        assertEquals(before, Locale.getDefault())
    }

    @Test
    fun `a number is formatted in the string's language, as Android's getString did`() {
        // The resources library would put toString() in: "3", where an Arabic sentence has "٣".
        assertEquals("الجلسات: ٣", get(AppLocale.ARABIC, Res.string.admin_session_count, 3))
        assertEquals("Sitzungen: 3", get(AppLocale.GERMAN, Res.string.admin_session_count, 3))
        // Emoji (qaa) has no number conventions of its own and writes English's (T-359).
        assertEquals("🗳️🔒: 3 / 4", get(AppLocale.EMOJI, Res.string.expense_agree_count, 3, 4))
        assertEquals(Locale.ENGLISH, AppLocale.EMOJI.toJavaLocale().forFormatting())
        assertEquals(Locale.GERMAN, AppLocale.GERMAN.toJavaLocale().forFormatting())
    }

    @Test
    fun `text is taken as written, without Android's escapes or trimming`() {
        // A leading space survives (T-341), and quotes and apostrophes need no backslash.
        assertEquals(" (admin)", get(AppLocale.ENGLISH, Res.string.admin_is_admin_suffix))
        assertEquals(
            "You'll stop receiving updates for \"Milk\" on this device.",
            get(AppLocale.ENGLISH, Res.string.listprops_leave_confirm_body, "Milk"),
        )
    }

    @Test
    fun `composition reads the language LocalizedContent applies`() {
        composeTestRule.setContent {
            LocalizedContent(AppLocale.UKRAINIAN) { Text(stringResource(Res.string.admin_session_count, 2)) }
        }

        composeTestRule.onNodeWithText("Сеанси: 2").assertIsDisplayed()
    }

    @Test
    fun `every string is read through Strings_kt, never the libraries' own functions`() {
        // The resources library's stringResource and getString take the process's language and put
        // numbers in unformatted; Android's own stringResource no longer has the strings at all.
        val forbidden = Regex(
            """^import (org\.jetbrains\.compose\.resources\.(stringResource|getString|pluralStringResource|getPluralString)|androidx\.compose\.ui\.res\.stringResource)\b""",
            RegexOption.MULTILINE,
        )
        // The shared sources and the app's (its notifications read strings too), from shared/,
        // the directory the tests run in.
        val sources = listOf("src/commonMain/kotlin", "src/androidMain/kotlin", "src/iosMain/kotlin", "../app/src/main/java")
            .flatMap { dir -> File(dir).walkTopDown().filter { it.extension == "kt" } }
        assertTrue("the sources are found", sources.size > 50)
        val offenders = sources.filter { it.name != "Strings.kt" && forbidden.containsMatchIn(it.readText()) }
        assertEquals(emptyList<String>(), offenders.map { it.name })
    }
}
