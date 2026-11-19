package my.fumui.sairitsu.gameplay

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * App-wide gameplay settings. Defaults: BGM volume 100 %, effect volume ("SFX") 25 % and no offsets. Offsets are
 * seconds; [inputOffset] is the global layer of [InputOffsets].
 */
data class AppSettings(
    val effectVolume: Float = 0.25f,
    val bgmVolume: Float = 1f,
    val audioOffset: Float = 0f,
    val inputOffset: Float = 0f,
) {
    /** In [NativeSettings] order. */
    fun toNative(): FloatArray = floatArrayOf(effectVolume, bgmVolume, audioOffset, inputOffset)
}

/** Where an input offset applies, narrowest first. */
enum class OffsetScope(val label: String) {
    Level("This level"),
    Server("This server"),
    Global("All levels"),
}

/** The input offset layers for one level, in seconds. A run uses their [total]. */
data class InputOffsets(val global: Float = 0f, val server: Float = 0f, val level: Float = 0f) {
    /** The sum in whole ms, so float rounding never reaches the runtime or a replay. */
    val total: Float
        get() = listOf(global, server, level).sumOf { (it * 1000f).roundToInt() } / 1000f

    fun of(scope: OffsetScope): Float = when (scope) {
        OffsetScope.Level -> level
        OffsetScope.Server -> server
        OffsetScope.Global -> global
    }

    fun with(scope: OffsetScope, value: Float): InputOffsets = when (scope) {
        OffsetScope.Level -> copy(level = value)
        OffsetScope.Server -> copy(server = value)
        OffsetScope.Global -> copy(global = value)
    }
}

internal val Context.gameplayStore by preferencesDataStore(name = "gameplay_settings")

/** Level Option values (per engine) and [AppSettings], in DataStore preferences. */
class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.gameplayStore)

    /** The saved values by option name for [engine]; options never saved are absent (they take their default). */
    fun options(engine: String): Flow<Map<String, Float>> {
        val prefix = optionPrefix(engine)
        return store.data.map { prefs ->
            prefs.asMap().mapNotNull { (key, value) ->
                if (key.name.startsWith(prefix) && value is Float) key.name.removePrefix(prefix) to value else null
            }.toMap()
        }
    }

    suspend fun saveOption(engine: String, name: String, value: Float) {
        store.edit { it[floatPreferencesKey(optionPrefix(engine) + name)] = value }
    }

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        val d = AppSettings()
        AppSettings(
            effectVolume = prefs[EFFECT_VOLUME] ?: d.effectVolume,
            bgmVolume = prefs[BGM_VOLUME] ?: d.bgmVolume,
            audioOffset = prefs[AUDIO_OFFSET] ?: d.audioOffset,
            inputOffset = prefs[INPUT_OFFSET] ?: d.inputOffset,
        )
    }

    suspend fun saveSettings(settings: AppSettings) {
        store.edit {
            it[EFFECT_VOLUME] = settings.effectVolume
            it[BGM_VOLUME] = settings.bgmVolume
            it[AUDIO_OFFSET] = settings.audioOffset
            it[INPUT_OFFSET] = settings.inputOffset
        }
    }

    /** The input offset layers for [level] on [server] (a [my.fumui.sairitsu.data.ServerAddress.linkAddress]). */
    fun inputOffsets(server: String, level: String): Flow<InputOffsets> = store.data.map { prefs ->
        InputOffsets(
            global = prefs[INPUT_OFFSET] ?: 0f,
            server = prefs[serverOffsetKey(server)] ?: 0f,
            level = prefs[levelOffsetKey(server, level)] ?: 0f,
        )
    }

    /** Saves one layer. A server or level value of 0 removes its key, so unused layers leave nothing behind. */
    suspend fun saveInputOffset(scope: OffsetScope, server: String, level: String, value: Float) {
        store.edit { prefs ->
            val key = when (scope) {
                OffsetScope.Global -> INPUT_OFFSET
                OffsetScope.Server -> serverOffsetKey(server)
                OffsetScope.Level -> levelOffsetKey(server, level)
            }
            if (value == 0f && scope != OffsetScope.Global) prefs.remove(key) else prefs[key] = value
        }
    }

    /** "Save levels I play": every level loaded from an online server is added to the library. Default off. */
    val saveLevelsIPlay: Flow<Boolean> = store.data.map { it[SAVE_LEVELS_I_PLAY] ?: false }

    suspend fun setSaveLevelsIPlay(on: Boolean) {
        store.edit { it[SAVE_LEVELS_I_PLAY] = on }
    }

    /** "Show FPS": the render thread's frames per second in a corner of the level. Default off. */
    val showFps: Flow<Boolean> = store.data.map { it[SHOW_FPS] ?: false }

    suspend fun setShowFps(on: Boolean) {
        store.edit { it[SHOW_FPS] = on }
    }

    /** The skin chosen for [level] on [server] (a [my.fumui.sairitsu.data.ServerAddress.linkAddress]). */
    fun levelSkin(server: String, level: String): Flow<SkinSelection> =
        store.data.map { decodeSkin(it[levelSkinKey(server, level)], SkinSelection.UseEngine) }

    /** The skin chosen for every level of [engine]. */
    fun engineSkin(engine: String): Flow<SkinSelection> =
        store.data.map { decodeSkin(it[engineSkinKey(engine)], SkinSelection.Original) }

    /** Saves the level's choice. Following the engine is the default, so it leaves nothing behind. */
    suspend fun saveLevelSkin(server: String, level: String, selection: SkinSelection) {
        store.edit { prefs ->
            val key = levelSkinKey(server, level)
            if (selection == SkinSelection.UseEngine) prefs.remove(key) else prefs[key] = encodeSkin(selection)
        }
    }

    /** Saves the engine's choice. The original skin is the default, so it leaves nothing behind. */
    suspend fun saveEngineSkin(engine: String, selection: SkinSelection) {
        store.edit { prefs ->
            val key = engineSkinKey(engine)
            if (selection is SkinSelection.Saved) prefs[key] = encodeSkin(selection) else prefs.remove(key)
        }
    }

    /** Clears every level and engine choice that names the saved skin [id]. */
    suspend fun clearSkin(id: String) {
        val value = encodeSkin(SkinSelection.Saved(id))
        store.edit { prefs ->
            prefs.asMap().filter { (key, saved) -> key.name.startsWith(SKIN_PREFIX) && saved == value }.keys
                .forEach { prefs.remove(stringPreferencesKey(it.name)) }
        }
    }

    private fun levelSkinKey(server: String, level: String) = stringPreferencesKey("${SKIN_PREFIX}level|$server|$level")

    private fun engineSkinKey(engine: String) = stringPreferencesKey("${SKIN_PREFIX}engine|$engine")

    private fun encodeSkin(selection: SkinSelection): String = when (selection) {
        is SkinSelection.Saved -> SAVED_SKIN + selection.id
        else -> ORIGINAL_SKIN
    }

    private fun decodeSkin(value: String?, default: SkinSelection): SkinSelection = when {
        value == ORIGINAL_SKIN -> SkinSelection.Original
        value != null && value.startsWith(SAVED_SKIN) -> SkinSelection.Saved(value.removePrefix(SAVED_SKIN))
        else -> default
    }

    private fun optionPrefix(engine: String) = "option|$engine|"

    private fun serverOffsetKey(server: String) = floatPreferencesKey("input_offset|server|$server")

    private fun levelOffsetKey(server: String, level: String) = floatPreferencesKey("input_offset|level|$server|$level")

    private companion object {
        const val SKIN_PREFIX = "skin|"
        const val ORIGINAL_SKIN = "original"
        const val SAVED_SKIN = "saved:"
        val SAVE_LEVELS_I_PLAY = booleanPreferencesKey("save_levels_i_play")
        val SHOW_FPS = booleanPreferencesKey("show_fps")
        val EFFECT_VOLUME = floatPreferencesKey("effect_volume")
        val BGM_VOLUME = floatPreferencesKey("bgm_volume")
        val AUDIO_OFFSET = floatPreferencesKey("audio_offset")
        val INPUT_OFFSET = floatPreferencesKey("input_offset")
    }
}
