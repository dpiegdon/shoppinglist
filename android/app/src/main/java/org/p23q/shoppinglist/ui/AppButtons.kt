package org.p23q.shoppinglist.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Every action on a screen is a real button; text-styled buttons remain only as a dialog's
 * confirm and dismiss. A button that destroys something (delete, leave, remove, revoke) takes
 * these colours; everything else keeps the default primary ones, and a secondary or cancel-like
 * action beside a primary one is outlined. The web's `btn-danger` is the same role.
 */
@Composable
fun dangerButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.error,
    contentColor = MaterialTheme.colorScheme.onError,
)

/** A button in a row beside a field or an entry, where the default padding would crowd it (the web's `btn-sm`). */
val CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
