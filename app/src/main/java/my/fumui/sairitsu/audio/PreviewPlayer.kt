package my.fumui.sairitsu.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PreviewStatus {
    data object Idle : PreviewStatus
    data class Loading(val url: String) : PreviewStatus
    data class Playing(val url: String) : PreviewStatus
    data class Failed(val url: String) : PreviewStatus
}

/** Plays one level preview at a time. */
class PreviewPlayer(context: Context) {
    private val player = ExoPlayer.Builder(context).build()
    private val _status = MutableStateFlow<PreviewStatus>(PreviewStatus.Idle)
    val status: StateFlow<PreviewStatus> = _status.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val url = currentUrl() ?: return
                _status.value = when (playbackState) {
                    Player.STATE_BUFFERING -> PreviewStatus.Loading(url)
                    Player.STATE_READY -> PreviewStatus.Playing(url)
                    Player.STATE_IDLE -> if (player.playerError != null) PreviewStatus.Failed(url) else PreviewStatus.Idle
                    Player.STATE_ENDED -> PreviewStatus.Idle
                    else -> _status.value
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _status.value = currentUrl()?.let { PreviewStatus.Failed(it) } ?: PreviewStatus.Idle
            }
        })
    }

    fun toggle(url: String) {
        val current = _status.value
        val active = (current is PreviewStatus.Loading && current.url == url) ||
            (current is PreviewStatus.Playing && current.url == url)
        if (active) {
            stop()
            return
        }
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
        _status.value = PreviewStatus.Loading(url)
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        _status.value = PreviewStatus.Idle
    }

    fun release() = player.release()

    private fun currentUrl(): String? = player.currentMediaItem?.localConfiguration?.uri?.toString()
}
