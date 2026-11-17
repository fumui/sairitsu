package my.fumui.sairitsu.play

import android.content.Context
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.LevelItem

/** Starts playing a level. Returns false when nothing could handle it. */
fun interface PlayLauncher {
    fun play(context: Context, server: ServerAddress, level: LevelItem): Boolean
}

/** The details pane's launchers: the Watch, Play and Level options buttons. */
data class Launchers(
    val watch: PlayLauncher,
    val play: PlayLauncher,
    val levelOptions: PlayLauncher,
)
