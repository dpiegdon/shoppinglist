package org.p23q.shoppinglist.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.p23q.shoppinglist.data.db.ItemEntity
import org.p23q.shoppinglist.data.db.Status

@Composable
fun ListScreen(
    onAddItem: () -> Unit,
    onEditItem: (itemId: String) -> Unit,
    viewModel: ListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.undoItemId) {
        val name = state.undoItemName
        val itemId = state.undoItemId
        if (itemId != null && name != null) {
            val result = snackbarHostState.showSnackbar(
                message = "$name checked",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoCheckOff()
            } else {
                viewModel.dismissUndo()
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onAddItem) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add item")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show checked")
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.showChecked, onCheckedChange = { viewModel.toggleShowChecked() })
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                state.groups.forEach { group ->
                    item(key = "header-${group.category ?: "—"}") {
                        Text(
                            text = group.category ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    items(group.items, key = { it.id }) { item ->
                        ItemRow(
                            item = item,
                            defaultCurrency = state.defaultCurrency,
                            onToggle = {
                                if (item.status.value == Status.CHECKED.wireValue) {
                                    viewModel.uncheck(item.id)
                                } else {
                                    viewModel.checkOff(item.id)
                                }
                            },
                            onEdit = { onEditItem(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: ItemEntity,
    defaultCurrency: String?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val isChecked = item.status.value == Status.CHECKED.wireValue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name.value,
                style = if (isChecked) {
                    MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = TextDecoration.LineThrough,
                        color = Color.Red,
                    )
                } else {
                    MaterialTheme.typography.bodyLarge
                },
            )
            formatPrice(item, defaultCurrency)?.let { price ->
                Text(text = price, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onEdit) {
            Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit ${item.name.value}")
        }
    }
}
