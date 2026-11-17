package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import my.fumui.sairitsu.audio.PreviewPlayer
import my.fumui.sairitsu.play.Launchers
import my.fumui.sairitsu.text.Localizer
import java.io.File

/** One collection's levels, in the same picker as Play; [vm] is scoped to the collection. */
@Composable
fun CollectionScreen(
    vm: LibraryViewModel,
    localizer: Localizer,
    player: PreviewPlayer,
    launchers: Launchers,
    cacheFile: (String) -> File?,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LibraryPicker(vm, state.collection?.name.orEmpty(), localizer, player, launchers, cacheFile, onBack, empty = { EmptyCollection() })
}

@Composable
private fun EmptyCollection() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No levels in this collection yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Long-press levels in Play to add them",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
