package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import my.fumui.sairitsu.gameplay.ItemPictures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.gameplay.ItemKind
import my.fumui.sairitsu.gameplay.SavedItem
import my.fumui.sairitsu.gameplay.ScopedItems
import my.fumui.sairitsu.library.downloadErrorText
import my.fumui.sairitsu.text.Localizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** A saved item: its picture, title and a remove action. */
@Composable
private fun SavedItemRow(item: SavedItem, title: String, pictures: ItemPictures, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ItemPicture(item, item.kind, pictures, Modifier.size(96.dp, 56.dp).clip(RoundedCornerShape(8.dp)))
        Text(title, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Settings section: the skins, backgrounds, SFX and particles saved on this device, grouped by kind, each with its
 * picture.
 */
@Composable
fun SavedItemsSection(items: ScopedItems, pictures: ItemPictures) {
    val all by items.library.items.collectAsState()
    val localizer = remember { Localizer(Localizer.serverLanguage(Locale.getDefault())) }
    var removing by remember { mutableStateOf<SavedItem?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SettingsSection("Saved items") {
        for (kind in ItemKind.entries) {
            val group = all.filter { it.kind == kind }
            if (group.isEmpty()) continue
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    kind.plural.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (item in group) {
                    SavedItemRow(item, localizer.text(item.title), pictures) { removing = item }
                }
            }
        }
        removeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Get more from a server's pages in Online Servers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove \"${localizer.text(item.title)}\"?") },
            text = { Text("Levels and engines using it go back to their own ${item.kind.label}.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    removeError = null
                    scope.launch {
                        try {
                            items.remove(item.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            removeError = downloadErrorText(e)
                        }
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
