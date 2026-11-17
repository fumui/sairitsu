package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import my.fumui.sairitsu.gameplay.SettingsStore
import my.fumui.sairitsu.library.Library
import my.fumui.sairitsu.library.StorageInfo
import kotlinx.coroutines.launch
import java.util.Locale

/** Global settings: volumes and offsets, "Save levels I play", "Show FPS", and how much storage downloads use. */
@Composable
fun SettingsScreen(settings: SettingsStore, library: Library, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val app by settings.settings.collectAsState(initial = null)
    val saveLevels by settings.saveLevelsIPlay.collectAsState(initial = false)
    val showFps by settings.showFps.collectAsState(initial = false)
    var storage by remember { mutableStateOf<StorageInfo?>(null) }
    val entries by library.index.entries.collectAsState()
    LaunchedEffect(entries) { storage = library.storage() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                app?.let { s -> AppSettingsSliders(s) { next: AppSettings -> scope.launch { settings.saveSettings(next) } } }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Save levels I play", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = saveLevels, onCheckedChange = { on -> scope.launch { settings.setSaveLevelsIPlay(on) } })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Show FPS", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = showFps, onCheckedChange = { on -> scope.launch { settings.setShowFps(on) } })
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                storage?.let { info ->
                    Text(
                        "%d levels downloaded, %s".format(Locale.ROOT, info.levels, megabytes(info.libraryBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Cache: %s".format(Locale.ROOT, megabytes(info.cacheBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "12.3 MB". */
fun megabytes(bytes: Long): String = "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
