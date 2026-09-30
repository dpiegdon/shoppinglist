package org.p23q.shoppinglist.ui.theme

import androidx.compose.material3.contentColorFor
import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {

    @Test
    fun `the system's dark or light setting picks the dark or light brand scheme`() {
        assertEquals(ThemeVariant.DARK, themeVariant(darkTheme = true))
        assertEquals(ThemeVariant.LIGHT, themeVariant(darkTheme = false))
    }

    @Test
    fun `the brand colours are the scheme decided for both clients (T-324)`() {
        // The web's tokens in web/src/index.css hold the same values, and a web test reads this
        // file; this one catches a hex edited here without opening the ticket's table.
        assertEquals(Color(0xFFFFFFFF), BackgroundLight)
        assertEquals(Color(0xFF000000), BackgroundDark)
        assertEquals(Color(0xFF1A1A1E), ForegroundLight)
        assertEquals(Color(0xFFF2F2F4), ForegroundDark)
        assertEquals(Color(0xFF5A97FF), AccentLight)
        assertEquals(Color(0xFF5A97FF), AccentDark)
        assertEquals(Color(0xFF0F62F0), AccentTextLight)
        assertEquals(Color(0xFF5A97FF), AccentTextDark)
        assertEquals(Color(0xFF0B1220), OnAccentLight)
        assertEquals(Color(0xFF0B1220), OnAccentDark)
    }

    private fun assertBuiltFrom(
        variant: ThemeVariant,
        background: Color,
        foreground: Color,
        accent: Color,
        accentText: Color,
        onAccent: Color,
        muted: Color,
        border: Color,
        surface: Color,
    ) {
        val scheme = brandColorScheme(variant)
        // Buttons, checkmarks, switches and the selected state are the accent.
        assertEquals(accent, scheme.primary)
        assertEquals(onAccent, scheme.onPrimary)
        assertEquals(accent, scheme.secondaryContainer)
        assertEquals(onAccent, scheme.onSecondaryContainer)
        // Highlighted text.
        assertEquals(accentText, scheme.accentText)
        // Flat: the background and the surface are one colour, and elevation adds no tint.
        assertEquals(background, scheme.background)
        assertEquals(background, scheme.surface)
        assertEquals(scheme.surface, scheme.surfaceTint)
        assertEquals(foreground, scheme.onBackground)
        assertEquals(foreground, scheme.onSurface)
        // Neutral greys, not Material's purple-tinted baseline.
        assertEquals(muted, scheme.onSurfaceVariant)
        assertEquals(border, scheme.outlineVariant)
        assertEquals(surface, scheme.surfaceVariant)
        listOf(
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
        ).forEach { assertEquals(surface, it) }
    }

    @Test
    fun `the light Material scheme is built from the light brand colours`() {
        assertBuiltFrom(
            ThemeVariant.LIGHT, BackgroundLight, ForegroundLight, AccentLight, AccentTextLight,
            OnAccentLight, MutedLight, BorderLight, SurfaceLight,
        )
    }

    @Test
    fun `the dark Material scheme is built from the dark brand colours`() {
        assertBuiltFrom(
            ThemeVariant.DARK, BackgroundDark, ForegroundDark, AccentDark, AccentTextDark,
            OnAccentDark, MutedDark, BorderDark, SurfaceDark,
        )
    }

    @Test
    fun `a card writes in the plain text colour, not the accent (T-337)`() {
        // A Material Card's default colours: surfaceContainerHighest, and the content colour
        // contentColorFor picks for it. tertiaryContainer shares that surface and is matched first.
        for (variant in ThemeVariant.entries) {
            val scheme = brandColorScheme(variant)
            assertEquals("$variant", scheme.onSurface, scheme.contentColorFor(scheme.surfaceContainerHighest))
        }
    }

    /** WCAG 2.1 relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun assertReadable(name: String, ink: Color, paper: Color) {
        val (lighter, darker) = listOf(luminance(ink), luminance(paper)).sortedDescending()
        val ratio = (lighter + 0.05) / (darker + 0.05)
        assertTrue("$name is $ratio:1, below WCAG AA's 4.5:1", ratio >= 4.5)
    }

    @Test
    fun `highlighted text and plain text clear WCAG AA on the background`() {
        for (variant in ThemeVariant.entries) {
            val scheme = brandColorScheme(variant)
            assertReadable("$variant highlighted text", scheme.accentText, scheme.background)
            // On cards and dialogs too: the overview's open counts, a dialog's text buttons (T-338).
            assertReadable("$variant highlighted text on a card", scheme.accentText, scheme.surfaceContainerHigh)
            assertReadable("$variant text", scheme.onBackground, scheme.background)
            assertReadable("$variant muted text", scheme.onSurfaceVariant, scheme.background)
        }
    }

    @Test
    fun `text on the accent and on the red clears WCAG AA, in both schemes (T-338)`() {
        for (variant in ThemeVariant.entries) {
            val scheme = brandColorScheme(variant)
            // Filled buttons and the add button; a selected chip or segment.
            assertReadable("$variant text on the accent", scheme.onPrimary, scheme.primary)
            assertReadable("$variant text on the selected state", scheme.onSecondaryContainer, scheme.secondaryContainer)
            // Every button that destroys something.
            assertReadable("$variant text on the red", scheme.onError, scheme.error)
        }
    }

    @Test
    fun `the balance green follows the light or dark theme`() {
        assertEquals(BalancePositiveLight, positiveBalanceColor(ThemeVariant.LIGHT))
        assertEquals(BalancePositiveDark, positiveBalanceColor(ThemeVariant.DARK))
    }

    @Test
    fun `the balance green is the web's --color-positive pair`() {
        // web/src/lib/balanceColors.test.ts checks the same pairing from the other side; this one
        // catches a hex edited here without opening the web's index.css.
        assertEquals(Color(0xFF15803D), BalancePositiveLight)
        assertEquals(Color(0xFF4ADE80), BalancePositiveDark)
    }

    /** The app's own sources, read from app/, the directory the tests run in. */
    private fun uiSources(): List<File> =
        File("src/main/java/org/p23q/shoppinglist").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `every text button is the theme's, whose label is readable (T-327)`() {
        val sources = uiSources()
        assertTrue("the sources are found", sources.size > 50)
        val offenders = sources.filter { it.name != "Theme.kt" && "import androidx.compose.material3.TextButton" in it.readText() }
        assertEquals("Material's TextButton labels in the accent; use TuppuTextButton", emptyList<String>(), offenders.map { it.name })
    }

    @Test
    fun `no text is drawn in the accent, which is too light to read on white (T-327)`() {
        // The accent fills things (buttons, the add button, the sync dot); text in its colour is
        // accentText, or the muted grey for a confirmation.
        val allowed = Regex("""containerColor(: Color)? = MaterialTheme\.colorScheme\.primary|else -> MaterialTheme\.colorScheme\.primary""")
        val offenders = uiSources().flatMap { file ->
            file.readLines().mapIndexedNotNull { at, line ->
                if (Regex("""colorScheme\.primary\b""").containsMatchIn(line) && !allowed.containsMatchIn(line)) "${file.name}:${at + 1}" else null
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the registration switch's meaning colours are the web's (T-327)`() {
        // The web's --color-switch-on and --color-switch-knob; web/src/lib/brandColors.test.ts
        // reads Theme.kt and fails if the two drift apart.
        assertEquals(Color(0xFF2E7D32), RegistrationSwitchOn)
        assertEquals(Color(0xFFFFFFFF), RegistrationSwitchKnob)
    }

    @Test
    fun `no colour value is named outside the theme (T-327)`() {
        val literal = Regex("""Color\(0x|Color\.(White|Black|Red|Green|Blue|Gray|DarkGray|LightGray|Yellow|Cyan|Magenta)\b""")
        val offenders = uiSources().filter { "/ui/theme/" !in it.invariantSeparatorsPath }.flatMap { file ->
            file.readLines().mapIndexedNotNull { at, line ->
                if (literal.containsMatchIn(line.substringBefore("//"))) "${file.name}:${at + 1}" else null
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the reds are the web's, in both schemes (T-333)`() {
        val light = brandColorScheme(ThemeVariant.LIGHT)
        val dark = brandColorScheme(ThemeVariant.DARK)
        assertEquals(Color(0xFFDC2626), light.error)
        assertEquals(Color(0xFFF87171), dark.error)
        assertEquals(Color(0xFFFFFFFF), light.onError)
        assertEquals(Color(0xFF1A0505), dark.onError)
        assertEquals(Color(0xFFFEF2F2), light.errorContainer)
        assertEquals(Color(0xFF2A1414), dark.errorContainer)
    }

    @Test
    fun `every filled button is TuppuButton, so a disabled one looks as on the web (T-334)`() {
        val offenders = uiSources().filter { it.name != "Theme.kt" && Regex("""import androidx\.compose\.material3\.Button$""", RegexOption.MULTILINE).containsMatchIn(it.readText()) }
        assertEquals("Material's Button greys out when disabled; use TuppuButton", emptyList<String>(), offenders.map { it.name })
    }

    @Test
    fun `every outlined button is TuppuOutlinedButton, its label in the text colour (T-343)`() {
        val offenders = uiSources().filter { it.name != "Theme.kt" && Regex("""import androidx\.compose\.material3\.OutlinedButton$""", RegexOption.MULTILINE).containsMatchIn(it.readText()) }
        assertEquals("Material's OutlinedButton labels in the accent; use TuppuOutlinedButton", emptyList<String>(), offenders.map { it.name })
    }

    @Test
    fun `every field-group label is the one muted FieldLabel (A15, T-343)`() {
        val text = uiSources().joinToString("\n") { it.readText() }
        val labels = listOf("item_stores", "item_status", "expense_type", "overview_type", "overview_new_list_account", "listprops_type")
        val missing = labels.filter { "FieldLabel(stringResource(R.string.$it))" !in text }
        assertEquals("drawn as FieldLabel", emptyList<String>(), missing)
    }

    @Test
    fun `a dialog's destructive confirm is drawn in the error colour (T-343)`() {
        // A confirm button whose label deletes, leaves or removes is a DangerTextButton (a category
        // merge saves, as on the web, and stays in the accent), never the accent TuppuTextButton. The block runs from
        // `confirmButton =` to the dismiss button, at most a few hundred characters.
        val destructive = Regex("""R\.string\.\w*(delete|leave|remove|revoke)\w*""")
        val blocks = uiSources().flatMap { file ->
            val text = file.readText()
            Regex("""confirmButton = \{""").findAll(text).map { match ->
                val rest = text.substring(match.range.first).take(500)
                val end = rest.indexOf("dismissButton").takeIf { it >= 0 } ?: rest.length
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                "${file.name}:$line" to rest.substring(0, end)
            }.toList()
        }
        val destructiveBlocks = blocks.filter { (_, block) -> destructive.containsMatchIn(block) }
        assertTrue("the destructive confirms are found: $destructiveBlocks", destructiveBlocks.size >= 7)
        val offenders = destructiveBlocks.filter { (_, block) -> "DangerTextButton(" !in block || "TuppuTextButton(" in block }.map { it.first }
        assertEquals("a destructive confirm must be a DangerTextButton", emptyList<String>(), offenders)
    }
}
