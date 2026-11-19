package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.text.Localizer

/** The second line of a level row: its engine and when it was last played; either part is left out when unknown. */
internal fun previewLevelDetail(level: PreviewLevel, localizer: Localizer): String =
    listOfNotNull(level.entry.engineTitle?.let(localizer::text),level.lastPlayed?.let { "played ${syncedAgo(it)}" }).joinToString(" · ")

/** Asks which downloaded level to preview [itemTitle] on; with none, offers to browse the server's levels. */
@Composable
fun PreviewLevelDialog(
    itemTitle: String,
    levels: List<PreviewLevel>,
    localizer: Localizer,
    onPreview: (PreviewLevel) -> Unit,
    onBrowse: () -> Unit,
    onCancel: () -> Unit,
) {
    var selectedName by remember { mutableStateOf<String?>(null) }
    // By name, so the choice stays on its level when the list reorders; the first row until one is picked.
    val selected = levels.firstOrNull { it.entry.name == selectedName } ?: levels.firstOrNull()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Preview \"$itemTitle\" on…") },
        text = {
            if (levels.isEmpty()) {
                Text("No downloaded levels from this server yet.")
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(levels, key = { level -> "level:" + level.entry.name }) { level ->
                        val isSelected = level.entry.name == selected?.entry?.name
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = isSelected, role = Role.RadioButton, onClick = { selectedName = level.entry.name })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = null, modifier = Modifier.padding(end = 12.dp).size(24.dp))
                            Column {
                                Text(localizer.text(level.entry.title), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val detail = previewLevelDetail(level, localizer)
                                if (detail.isNotEmpty()) {
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (levels.isEmpty()) {
                TextButton(onClick = onBrowse) { Text("Browse levels") }
            } else {
                TextButton(onClick = { selected?.let(onPreview) }) { Text("Preview") }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
