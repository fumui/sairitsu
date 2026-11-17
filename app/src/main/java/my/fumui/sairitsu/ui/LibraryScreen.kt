package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.audio.PreviewPlayer
import my.fumui.sairitsu.play.Launchers
import my.fumui.sairitsu.text.Localizer
import java.io.File

/** Play: the downloaded levels, playable offline, with search, filters and sort. */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    localizer: Localizer,
    player: PreviewPlayer,
    launchers: Launchers,
    cacheFile: (String) -> File?,
    onServers: () -> Unit,
    onBack: () -> Unit,
) {
    LibraryPicker(vm, "Play", localizer, player, launchers, cacheFile, onBack, empty = { EmptyLibrary(onServers) })
}

@Composable
private fun EmptyLibrary(onServers: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No downloaded levels yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onServers) { Text("Online Servers") }
    }
}
