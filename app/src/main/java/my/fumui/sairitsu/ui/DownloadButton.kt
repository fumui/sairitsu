package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.gameplay.LoadProgress
import my.fumui.sairitsu.library.DownloadState

/** What the browser's Download button shows for a level. */
sealed interface DownloadButtonState {
    data object NotDownloaded : DownloadButtonState

    data class InProgress(val progress: LoadProgress?) : DownloadButtonState

    data object Downloaded : DownloadButtonState

    data class Failed(val message: String) : DownloadButtonState
}

/** [downloaded] (in the library) wins over any download state. */
fun downloadButtonState(downloaded: Boolean, state: DownloadState?): DownloadButtonState = when {
    downloaded -> DownloadButtonState.Downloaded
    state is DownloadState.Queued -> DownloadButtonState.InProgress(null)
    state is DownloadState.Running -> DownloadButtonState.InProgress(state.progress)
    state is DownloadState.Failed -> DownloadButtonState.Failed(state.message)
    else -> DownloadButtonState.NotDownloaded
}

/** "Download", progress (tap to cancel), a disabled check icon once downloaded, or "Retry". */
@Composable
fun DownloadButton(state: DownloadButtonState, onDownload: () -> Unit, onCancel: () -> Unit) {
    when (state) {
        DownloadButtonState.NotDownloaded -> OutlinedButton(onClick = onDownload) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Download")
        }
        is DownloadButtonState.InProgress -> OutlinedButton(onClick = onCancel) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(state.progress?.let { "${it.done}/${it.total}" } ?: "0/15")
        }
        DownloadButtonState.Downloaded -> OutlinedIconButton(onClick = {}, enabled = false) {
            Icon(Icons.Outlined.DownloadDone, contentDescription = "Downloaded")
        }
        is DownloadButtonState.Failed -> OutlinedButton(onClick = onDownload) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Retry")
        }
    }
}
