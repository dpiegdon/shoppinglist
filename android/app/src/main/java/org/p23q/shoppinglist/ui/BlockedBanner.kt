package org.p23q.shoppinglist.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.p23q.shoppinglist.R

/**
 * The server refused this row and the device parked it. Shown at the top of the form that opened
 * it, where whoever came to fix it is already looking: the sync status bar counts the parked rows
 * but says neither which nor why.
 *
 * [code] is what the server answered, [who] the participant it named where it named one, already
 * labelled the way this form labels people. One composable for the expense form (T-200) and the
 * item form (T-210) — they say the same thing, so they should not drift apart.
 */
@Composable
fun BlockedBanner(code: String?, who: String?) {
    val reason = ErrorText.refusal(code, who)?.asString()
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // expense_not_saved, whose English is generic on purpose: "the list" is whichever list
            // this row belongs to, so the item form says it too rather than owning a second string.
            Text(stringResource(R.string.expense_not_saved), style = MaterialTheme.typography.titleSmall)
            // Only when the refusal says more than the heading already does.
            if (reason != null) {
                Spacer(Modifier.height(4.dp))
                Text(reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}
