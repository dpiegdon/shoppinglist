package org.p23q.shoppinglist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mechanical integrity of the translated string resources (T-124).
 *
 * These are the ONLY checks available for six of the nine languages — nobody working on this repo
 * can read Japanese, Ukrainian or Arabic well enough to spot a wrong word, so what can be verified
 * must be verified automatically:
 *
 * - key parity, or a string silently falls back to English while looking translated;
 * - format-argument parity, or a translation drops a value or drops it into the wrong slot —
 *   which Android does not catch until the format call throws at runtime;
 * - stray-script detection, added after finding Cyrillic spliced into a Japanese string. That
 *   class of corruption is invisible to a reader who cannot read the language.
 *
 * Parsed as text rather than via Resources: this asserts what is in the files, independent of
 * whatever the current device locale happens to resolve to.
 */
class TranslationResourcesTest {

    private val resDir = File("src/main/res")

    private val stringPattern = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val formatArg = Regex("""%\d+\$[sd]""")

    /** Scripts each locale has no business containing, as a corruption tripwire. */
    private val strayScript = mapOf(
        "values-de" to Regex("[\\u0400-\\u04FF\\u0600-\\u06FF\\u4E00-\\u9FFF]"),
        "values-es" to Regex("[\\u0400-\\u04FF\\u0600-\\u06FF\\u4E00-\\u9FFF]"),
        "values-fr" to Regex("[\\u0400-\\u04FF\\u0600-\\u06FF\\u4E00-\\u9FFF]"),
        "values-pt-rBR" to Regex("[\\u0400-\\u04FF\\u0600-\\u06FF\\u4E00-\\u9FFF]"),
        "values-ja" to Regex("[\\u0400-\\u04FF\\u0600-\\u06FF]"),
        "values-b+zh+Hans" to Regex("[\\u0400-\\u04FF\\u0600-\\u06FF]"),
        "values-uk" to Regex("[\\u0600-\\u06FF\\u4E00-\\u9FFF]"),
        "values-ar" to Regex("[\\u0400-\\u04FF\\u4E00-\\u9FFF]"),
    )

    private fun read(dir: String): Map<String, String> {
        val file = File(resDir, "$dir/strings.xml")
        assertTrue("missing $dir/strings.xml", file.exists())
        return stringPattern.findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val english = read("values")

    @Test
    fun `every locale covers every English key`() {
        // A missing key falls back to English at runtime, so the app still works — which is exactly
        // why this needs a test: a half-translated language looks fine until a user reports it.
        strayScript.keys.forEach { dir ->
            val translated = read(dir)
            assertEquals("$dir key count", english.keys, translated.keys)
        }
    }

    @Test
    fun `no locale invents a key English does not have`() {
        strayScript.keys.forEach { dir ->
            val extra = read(dir).keys - english.keys
            assertTrue("$dir has unknown keys: $extra", extra.isEmpty())
        }
    }

    @Test
    fun `format arguments survive translation intact`() {
        // Dropping %1$s reads perfectly and silently loses the value; reordering it into the wrong
        // slot throws at format time. Neither is visible to a reader of the language.
        strayScript.keys.forEach { dir ->
            read(dir).forEach { (key, value) ->
                val source = english[key] ?: return@forEach
                assertEquals(
                    "$dir/$key format args",
                    formatArg.findAll(source).map { it.value }.sorted().toList(),
                    formatArg.findAll(value).map { it.value }.sorted().toList(),
                )
            }
        }
    }

    @Test
    fun `no locale contains characters from a script it has no business using`() {
        strayScript.forEach { (dir, pattern) ->
            read(dir).forEach { (key, value) ->
                assertTrue("$dir/$key contains stray script: $value", !pattern.containsMatchIn(value))
            }
        }
    }

    @Test
    fun `no translated string is empty`() {
        strayScript.keys.forEach { dir ->
            read(dir).forEach { (key, value) ->
                assertTrue("$dir/$key is empty", value.isNotBlank())
            }
        }
    }
}
