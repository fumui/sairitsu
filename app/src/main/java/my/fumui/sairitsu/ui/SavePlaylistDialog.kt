package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.PlaylistSummary
import my.fumui.sairitsu.library.playlistCollectionName
import my.fumui.sairitsu.text.Localizer

/** What the playlist screens need from the library: what is downloaded or downloading, failures, and the save action. */
class PlaylistDownloads(
    val downloaded: Set<LevelKey>,
    val active: Set<LevelKey>,
    /** Download failures of this server's levels, as snackbar text. */
    val failures: List<String>,
    /** Downloads [levels] into the collection [collectionName]; a failure means the collections couldn't be saved. */
    val onSave: suspend (collectionName: String, levels: List<LevelItem>) -> Result<String>,
)

const val SAVE_COLLECTIONS_FAILED = "Couldn't save collections"

/**
 * Confirms "Download all as collection". [playlist] is the playlist's details as they load: the dialog opens at once, so
 * a tap meant for it never lands on the list underneath while the details are on their way.
 */
@Composable
fun SavePlaylistDialog(
    playlist: Loadable<PlaylistSummary>,
    server: ServerAddress,
    localizer: Localizer,
    downloads: PlaylistDownloads,
    onConfirm: (collectionName: String, levels: List<LevelItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    val ready = (playlist as? Loadable.Ready)?.value
    val name = ready?.let { playlistCollectionName(localizer.text(it.title), it.name) }
    val total = ready?.levels?.size ?: 0
    val new = ready?.levels?.count { LevelKey(server.linkAddress, it.name) !in downloads.downloaded } ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download all as collection?") },
        text = {
            when {
                playlist is Loadable.Error -> Text(playlist.message, color = MaterialTheme.colorScheme.error)
                ready == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                new > 0 -> Text("Download $new levels and add all $total to the collection \"$name\".")
                else -> Text("All $total levels are downloaded. Add them to the collection \"$name\".")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (ready != null && name != null) onConfirm(name, ready.levels) },
                enabled = ready != null && total > 0,
            ) { Text(if (ready != null && new == 0) "Add" else "Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
