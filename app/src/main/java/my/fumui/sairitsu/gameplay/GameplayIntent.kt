package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.ServerAddress
import java.io.File

enum class GameplayMode(val wire: String) { Watch("watch"), Play("play") }

/**
 * Debug-only render-at-time: render the level at [times] (music time, s) with Level Option
 * values [options] (engine configuration order; fewer take the defaults) into PNGs in [outDir] instead of running
 * it, then write [DONE_FILE] there: "ok <count>" or "error: <text>". [error] set: the request's files could not be
 * read; nothing runs and the error is written instead.
 */
class RenderRequest(
    val times: FloatArray,
    val outDir: File,
    val options: FloatArray,
    val error: String? = null,
) {
    companion object {
        const val DONE_FILE = "done.txt"

        /** A times or options file pushed by the PC: one value per line (blank lines ignored). */
        fun parseValues(text: String): FloatArray =
            text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray()
    }
}

/**
 * A saved replay to watch: its store [id] and the level data and engine keys it was recorded with (null: not known,
 * which counts as changed).
 */
data class ReplayRef(val id: String, val levelData: String?, val engine: String?)

/** What [GameplayActivity] was asked to run. */
data class GameplayRequest(
    val server: ServerAddress,
    val level: String,
    val mode: GameplayMode,
    /** The level's engine name, which the saved Level Options belong to; null: a shared default. */
    val engine: String? = null,
    /** Show the "Level options" screen instead of running the level. */
    val optionsScreen: Boolean = false,
    /** Watch a saved replay instead of the chart's autoplay; always null in Play. */
    val replay: ReplayRef? = null,
)

/** Intent extras of [GameplayActivity]. Pure, so it is unit-testable. */
object GameplayIntent {
    const val SERVER = "server"
    const val LEVEL = "level"
    const val MODE = "mode"
    const val ENGINE = "engine"
    const val SCREEN = "screen"
    const val SCREEN_OPTIONS = "options"
    const val REPLAY = "replay"
    const val REPLAY_LEVEL_DATA = "replayLevelData"
    const val REPLAY_ENGINE = "replayEngine"

    // Debug-only extras, honoured only through the DebugGameplayEntry alias.
    const val OFFLINE = "offline"
    const val RENDER_TIMES = "renderTimes"
    const val OUT_DIR = "outDir"
    const val RENDER_OPTIONS = "renderOptions" // with renderTimes: a file of Level Option values, one per line
    const val EFFECT_VOLUME = "effectVolume" // 0-1, until the settings screen lands
    const val BGM_VOLUME = "bgmVolume" // 0-1
    const val AUDIO_OFFSET = "audioOffset" // seconds
    const val REPLAY_CONFIG = "replayConfig" // a file: a replay `config`, saved as the Level Options

    /** Whether any debug audio extra is given (the settings then replace the saved ones). */
    fun hasSettings(extra: (String) -> String?): Boolean =
        listOf(EFFECT_VOLUME, BGM_VOLUME, AUDIO_OFFSET).any { extra(it) != null }

    /** [NativeSettings.defaults] with the debug audio extras that parse as numbers put in. */
    fun settings(extra: (String) -> String?): FloatArray {
        val out = NativeSettings.defaults()
        for ((key, index) in listOf(
            EFFECT_VOLUME to NativeSettings.EFFECT_VOLUME,
            BGM_VOLUME to NativeSettings.BGM_VOLUME,
            AUDIO_OFFSET to NativeSettings.AUDIO_OFFSET,
        )) {
            extra(key)?.toFloatOrNull()?.let { out[index] = it }
        }
        return out
    }

    fun extras(
        server: ServerAddress,
        level: String,
        mode: GameplayMode,
        engine: String? = null,
        optionsScreen: Boolean = false,
        replay: ReplayRef? = null,
    ): Map<String, String> = buildMap {
        put(SERVER, server.baseUrl.toString().trimEnd('/'))
        put(LEVEL, level)
        put(MODE, mode.wire)
        engine?.let { put(ENGINE, it) }
        if (optionsScreen) put(SCREEN, SCREEN_OPTIONS)
        if (replay != null) {
            put(REPLAY, replay.id)
            replay.levelData?.let { put(REPLAY_LEVEL_DATA, it) }
            replay.engine?.let { put(REPLAY_ENGINE, it) }
        }
    }

    /** Reads the request back from string extras; null when the server or level is missing or invalid. */
    fun parse(extra: (String) -> String?): GameplayRequest? {
        val server = extra(SERVER)?.let(ServerAddress::parse) ?: return null
        val level = extra(LEVEL)?.takeIf { it.isNotBlank() } ?: return null
        val mode = when (val wire = extra(MODE)) {
            null -> GameplayMode.Watch
            else -> GameplayMode.entries.firstOrNull { it.wire == wire } ?: return null
        }
        val replay = extra(REPLAY)?.takeIf { it.isNotBlank() }?.let {
            if (mode != GameplayMode.Watch) return null
            ReplayRef(it, extra(REPLAY_LEVEL_DATA), extra(REPLAY_ENGINE))
        }
        return GameplayRequest(
            server, level, mode, extra(ENGINE)?.takeIf { it.isNotBlank() }, extra(SCREEN) == SCREEN_OPTIONS, replay,
        )
    }
}
