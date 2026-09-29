package org.p23q.shoppinglist.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The colour of a section card's border, for tests: a border draws nothing semantics can see. */
val SectionCardBorder = SemanticsPropertyKey<Color>("SectionCardBorder")
private var SemanticsPropertyReceiver.sectionCardBorder by SectionCardBorder

/**
 * One section of the Settings, Account or Admin screen, in a card as the web shows it (its
 * `.card` with 1rem padding and the title inside). The overview's card colour (the web's
 * `--color-surface`). A [danger] section, the web's delete-account card, has a 1dp border and its
 * title in the error colour.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = if (danger) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null
    Card(
        border = border,
        modifier = modifier
            .fillMaxWidth()
            .testTag("section-card")
            .semantics { (border?.brush as? SolidColor)?.let { sectionCardBorder = it.value } },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            // The web's h2 keeps its bottom margin.
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
