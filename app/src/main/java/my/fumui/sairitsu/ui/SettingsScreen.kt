package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.gameplay.AppSettings
import my.fumui.sairitsu.gameplay.ItemPictures
import my.fumui.sairitsu.gameplay.ScopedItems
import my.fumui.sairitsu.gameplay.SettingsStore
import my.fumui.sairitsu.library.Library
import my.fumui.sairitsu.library.StorageInfo
import kotlinx.coroutines.launch
import java.util.Locale

/** Global settings: volumes and offsets, "Save levels I play", "Show FPS", saved items and storage use. */
@Composable
fun SettingsScreen(settings: SettingsStore, library: Library, items: ScopedItems, pictures: ItemPictures, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val app by settings.settings.collectAsState(initial = null)
    val saveLevels by settings.saveLevelsIPlay.collectAsState(initial = false)
    val showFps by settings.showFps.collectAsState(initial = false)
    var storage by remember { mutableStateOf<StorageInfo?>(null) }
    val entries by library.index.entries.collectAsState()
    LaunchedEffect(entries) { storage = library.storage() }

    SettingsFrame("Settings", onBack) {
        SettingsSection("Audio & input", first = true) {
            app?.let { s -> AppSettingsSliders(s) { next: AppSettings -> scope.launch { settings.saveSettings(next) } } }
        }
        SettingsSection("General") {
            SwitchRow("Save levels I play", saveLevels) { on -> scope.launch { settings.setSaveLevelsIPlay(on) } }
            SwitchRow("Show FPS", showFps) { on -> scope.launch { settings.setShowFps(on) } }
        }
        SavedItemsSection(items, pictures)
        SettingsSection("Storage") {
            storage?.let { info ->
                Column {
                    Text(
                        "%d levels downloaded, %s".format(Locale.ROOT, info.levels, megabytes(info.libraryBytes)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Cache: %s".format(Locale.ROOT, megabytes(info.cacheBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) { OptionLabel(label) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** "12.3 MB". */
fun megabytes(bytes: Long): String = "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
