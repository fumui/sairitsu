package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.PlaylistSummary
import my.fumui.sairitsu.library.PlaylistRowState
import my.fumui.sairitsu.library.playlistProgress
import my.fumui.sairitsu.text.Localizer

/** One playlist: its levels, with a check on the downloaded ones, and "Download all as collection". */
@Composable
fun PlaylistScreen(
    vm: PlaylistViewModel,
    server: ServerAddress,
    localizer: Localizer,
    downloads: PlaylistDownloads,
    onBack: () -> Unit,
) {
    val loadable by vm.playlist.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    PlaylistFailures(downloads, snackbar)
    var confirming by remember { mutableStateOf<PlaylistSummary?>(null) }
    val playlist = (loadable as? Loadable.Ready)?.value

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.displayCutout)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text(
                    playlist?.let { localizer.text(it.title) }.orEmpty(),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playlist != null && playlist.levels.isNotEmpty()) {
                    val progress = playlistProgress(server.linkAddress, playlist.levels, downloads.downloaded, downloads.active)
                    val downloading = progress.state == PlaylistRowState.Downloading
                    Button(onClick = { confirming = playlist }, enabled = !downloading) {
                        Text(if (downloading) "Downloading ${progress.saved} / ${progress.total}" else "Download all as collection")
                    }
                }
            }
            HorizontalDivider()
            Box(Modifier.fillMaxSize()) {
                when (val state = loadable) {
                    is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        items(state.value.levels, key = { "level:" + it.name }) { level ->
                            LevelRow(level, LevelKey(server.linkAddress, level.name) in downloads.downloaded, server, localizer)
                        }
                    }
                    is Loadable.Error -> ErrorMessage(state.message, vm::retry, Modifier.align(Alignment.Center))
                    else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }
    confirming?.let { shown ->
        SavePlaylistDialog(
            Loadable.Ready(shown), server, localizer, downloads,
            onConfirm = { name, levels ->
                confirming = null
                scope.launch { if (downloads.onSave(name, levels).isFailure) snackbar.showSnackbar(SAVE_COLLECTIONS_FAILED) }
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun LevelRow(level: LevelItem, downloaded: Boolean, server: ServerAddress, localizer: Localizer) {
    val title = localizer.text(level.title)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(server.resolve(level.cover?.url), 56, title = title)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                byline(level, localizer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RatingBadge(level.rating)
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (downloaded) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
