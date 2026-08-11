package org.p23q.shoppinglist.ui.item

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import org.p23q.shoppinglist.ui.LocalizedOverlay
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.ui.asString

/** Notes (Add-item dialog): name suggestions from the registry; picking one reuses it, else creates new.
 *
 *  Full-screen, and laid out like [EditItemDialog] for the same reason (T-80): the action bar is
 *  fixed below a scrolling field area, so the soft keyboard can't cover it. As a floating
 *  AlertDialog it did — Add and Cancel sat under the keyboard, and adding a single item meant
 *  dismissing the keyboard first, on the screen you reach most often in the app. */
@Composable
fun AddItemDialog(
    listId: String,
    onDismiss: () -> Unit,
    viewModel: ItemFormViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nameFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(listId) { viewModel.startAdd(listId) }
    LaunchedEffect(state.isSaved) { if (state.isSaved) onDismiss() }
    // Open the dialog and just start typing. The frame wait is load-bearing — this dialog is a
    // window of its own, and a focus request placed before that window exists is dropped, taking
    // the keyboard with it.
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { nameFocusRequester.requestFocus() }
        keyboard?.show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Full-screen and no dismiss-on-click-outside (T-80); back-press still cancels.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // A plain Compose Dialog window fits system windows and reports the IME inset as 0, so
        // safeDrawingPadding() couldn't lift the action bar above the keyboard. Opt this dialog's
        // window out of decor-fits-system-windows so the keyboard becomes a real inset (T-80).
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.let { WindowCompat.setDecorFitsSystemWindows(it, false) } }

        // This is a window of its own, so the chosen language has to be applied again (T-131).
        LocalizedOverlay {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                        Text(
                            stringResource(R.string.item_add),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                    }
                    HorizontalDivider()

                    // Scrollable field area filling the gap between the fixed header and action bar.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = viewModel::onNameChange,
                            label = { Text(stringResource(R.string.item_name)) },
                            singleLine = true,
                            isError = state.nameError != null,
                            modifier = Modifier.fillMaxWidth().focusRequester(nameFocusRequester),
                        )
                        state.nameError?.let { error ->
                            Text(text = error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.itemId == null && state.suggestions.isNotEmpty()) {
                            Column {
                                state.suggestions.forEach { suggestion ->
                                    Text(
                                        text = suggestion.name.value,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.pickSuggestion(suggestion) }
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        ItemFormFields(state = state, viewModel = viewModel)
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = viewModel::save) { Text(stringResource(R.string.action_add)) }
                    }
                }
            }
        }
    }
}
