package my.fumui.sairitsu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.PlaylistSummary
import my.fumui.sairitsu.library.PlaylistProgress
import my.fumui.sairitsu.library.PlaylistRowState
import my.fumui.sairitsu.library.playlistProgress
import my.fumui.sairitsu.text.Localizer
import kotlinx.coroutines.launch

/** A server's playlists; each row downloads its playlist into a collection, and a tap opens it. */
@Composable
fun PlaylistsScreen(
    vm: PlaylistsViewModel,
    server: ServerAddress,
    localizer: Localizer,
    downloads: PlaylistDownloads,
    onOpen: (name: String) -> Unit,
    onBack: () -> Unit,
) {
    val playlists by vm.playlists.collectAsState()
    val paged by vm.pager.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, paged, vm.pager::loadMore)
    PlaylistFailures(downloads, snackbar)
    val saveRequest by vm.saveRequest.collectAsState()

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.displayCutout)) {
            ScreenTopRow("Playlists", onBack)
            HorizontalDivider()
            Box(Modifier.fillMaxSize()) {
                if (playlists.isEmpty()) {
                    EmptyPagerState(paged, "This server has no playlists", vm.pager::retry, Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        items(playlists, key = { "item:" + it.name }) { playlist ->
                            val progress = playlistProgress(server.linkAddress, playlist.levels, downloads.downloaded, downloads.active)
                            PlaylistRow(
                                playlist, progress, server, localizer,
                                onOpen = { onOpen(playlist.name) },
                                // The list item may hold only some levels: the dialog loads the details.
                                onDownload = { vm.requestSave(playlist.name) },
                            )
                        }
                        item(key = "footer") { PagerFooter(paged, vm.pager::retry) }
                    }
                }
            }
        }
    }
    saveRequest?.let { request ->
        SavePlaylistDialog(
            request.playlist, server, localizer, downloads,
            onConfirm = { name, levels ->
                vm.dismissSave()
                scope.launch { if (downloads.onSave(name, levels).isFailure) snackbar.showSnackbar(SAVE_COLLECTIONS_FAILED) }
            },
            onDismiss = vm::dismissSave,
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistSummary,
    progress: PlaylistProgress,

    server: ServerAddress,
    localizer: Localizer,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val title = localizer.text(playlist.title)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(server.resolve(playlist.thumbnail?.url), 64, title = title)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val byline = listOf(localizer.text(playlist.subtitle), localizer.text(playlist.author)).filter { it.isNotBlank() }.joinToString(" · ")
            if (byline.isNotEmpty()) {
                Text(
                    byline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PlaylistAction(progress, title, onDownload)
    }
}

/** The row's trailing state: progress, a download button, or the saved check. */
@Composable
private fun PlaylistAction(progress: PlaylistProgress, title: String, onDownload: () -> Unit) {
    val count = "${progress.saved} / ${progress.total}"
    val showCount = progress.state == PlaylistRowState.Downloading || progress.state == PlaylistRowState.PartlySaved
    if (showCount) {
        Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when {
        progress.state == PlaylistRowState.Downloading ->
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        progress.state == PlaylistRowState.AllSaved -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = "Saved", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> IconButton(onClick = onDownload) {
            Icon(Icons.Outlined.Download, contentDescription = "Download $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shows each new download failure of this server's levels in [snackbar]. */
@Composable
internal fun PlaylistFailures(downloads: PlaylistDownloads, snackbar: SnackbarHostState) {
    LaunchedEffect(downloads.failures) {
        for (message in downloads.failures) snackbar.showSnackbar(message)
    }
}
