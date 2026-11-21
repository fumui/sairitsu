package my.fumui.sairitsu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.library.Membership
import my.fumui.sairitsu.library.tapAdds

/** One collection in [AddToCollectionDialog]: whether the levels are in it, and whether that waits on a download. */
data class CollectionRow(val id: String, val name: String, val state: Membership, val pending: Boolean = false)

/**
 * "Add to collection": a box per collection (all, some or none of the levels are in it) and "New collection…". Each
 * tap saves right away through [onToggle] (add = true adds the levels, false removes them); Done only closes.
 */
@Composable
fun AddToCollectionDialog(
    rows: List<CollectionRow>,
    onToggle: (id: String, add: Boolean) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to collection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    val toggle = { onToggle(row.id, row.state.tapAdds()) }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(
                            state = when (row.state) {
                                Membership.All -> ToggleableState.On
                                Membership.Some -> ToggleableState.Indeterminate
                                Membership.None -> ToggleableState.Off
                            },
                            onClick = toggle,
                        )
                        Column {
                            Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (row.pending) {
                                Text(
                                    "Joins after download",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onNew).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(20.dp))
                    }
                    Text("New collection…")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Asks for a collection name; [validate] gives the error to show (null = OK), shown once the text has changed. */
@Composable
fun CollectionNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    validate: (String) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var edited by remember { mutableStateOf(false) }
    val error = validate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    edited = true
                },
                singleLine = true,
                placeholder = { Text("Name") },
                isError = edited && error != null,
                supportingText = { if (edited && error != null) Text(error) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = error == null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The picker's top row in selection mode. [removeLabel] null hides the ⋮ menu; [onDelete] null hides Delete. */
@Composable
fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    removeLabel: String?,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAddToCollection: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close selection") }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onSelectAll) { Text(if (allSelected) "Select none" else "Select all") }
        IconButton(onClick = onAddToCollection) { Icon(Icons.Outlined.BookmarkAdd, contentDescription = "Add to collection") }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
        }
        if (removeLabel != null) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(removeLabel) },
                        onClick = {
                            open = false
                            onRemove()
                        },
                    )
                }
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}
