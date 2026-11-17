package my.fumui.sairitsu.play

import android.content.Context
import android.content.Intent
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.gameplay.GameplayActivity
import my.fumui.sairitsu.gameplay.GameplayIntent
import my.fumui.sairitsu.gameplay.GameplayMode

object GameplayFeatures {
    /** Play mode is shown in the picker. */
    const val PLAY_ENABLED = true
}

/** Runs the level in this app's own runtime. */
class InAppPlayLauncher(
    private val mode: GameplayMode,
    /** Open the level's options instead of running it. */
    private val optionsScreen: Boolean = false,
) : PlayLauncher {
    override fun play(context: Context, server: ServerAddress, level: LevelItem): Boolean {
        val intent = Intent(context, GameplayActivity::class.java)
        GameplayIntent.extras(server, level.name, mode, level.engine?.name, optionsScreen).forEach { (key, value) -> intent.putExtra(key, value) }
        context.startActivity(intent)
        return true
    }
}
