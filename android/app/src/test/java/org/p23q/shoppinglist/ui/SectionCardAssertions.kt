package org.p23q.shoppinglist.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.p23q.shoppinglist.ui.theme.ThemeVariant
import org.p23q.shoppinglist.ui.theme.brandColorScheme

/** The light brand scheme, so a test can compare against its error colour. */
val sectionTestScheme = brandColorScheme(ThemeVariant.LIGHT)

/** [content] in the app's light colours. */
@Composable
fun InBrandColors(content: @Composable () -> Unit) = MaterialTheme(colorScheme = sectionTestScheme, content = content)

/** The section card whose title is [title] (T-336). */
private fun ComposeContentTestRule.sectionCard(title: String) =
    onNode(hasTestTag("section-card") and hasAnyDescendant(hasText(title)))

private fun ComposeContentTestRule.titleColor(title: String) =
    // The first: a button in the card may carry the same words ("Change password").
    onAllNodes(hasText(title) and hasAnyAncestor(hasTestTag("section-card")), useUnmergedTree = true)[0].fetchSemanticsNode().let { node ->
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        results.first().layoutInput.style.color
    }

/** Each of [titles] heads a plain section card: no border, the title in the plain text colour. */
fun ComposeContentTestRule.assertPlainSectionCards(vararg titles: String) = titles.forEach { title ->
    sectionCard(title).performScrollTo()
    assertNull(title, sectionCard(title).fetchSemanticsNode().config.getOrElseNullable(SectionCardBorder) { null })
    // Not the accent: the card's default content colour would be (the scheme's tertiaryContainer
    // shares the surface colour).
    assertEquals(title, sectionTestScheme.onSurface, titleColor(title))
}

/** [title] heads the danger card: a border and a title both in the error colour, as the web's. */
fun ComposeContentTestRule.assertDangerSectionCard(title: String) {
    sectionCard(title).performScrollTo()
    assertEquals(sectionTestScheme.error, sectionCard(title).fetchSemanticsNode().config.getOrElseNullable(SectionCardBorder) { null })
    assertEquals(sectionTestScheme.error, titleColor(title))
}

/**
 * The section cards headed by [titles] stand one below the other in exactly this order (T-337).
 * By their laid-out positions, which a scrolled column keeps for the cards off screen too.
 */
fun ComposeContentTestRule.assertSectionCardOrder(vararg titles: String) {
    val tops = titles.map { title -> sectionCard(title).fetchSemanticsNode().positionInRoot.y }
    assertEquals(titles.toList(), titles.zip(tops).sortedBy { it.second }.map { it.first })
    assertEquals("two cards at one height", tops.size, tops.toSet().size)
}

/** Something showing [text] sits inside the section card headed by [title] (T-337). */
fun ComposeContentTestRule.assertInSectionCard(title: String, text: String) {
    onNode(
        hasText(text) and hasAnyAncestor(hasTestTag("section-card") and hasAnyDescendant(hasText(title))),
        useUnmergedTree = true,
    ).assertExists()
}
