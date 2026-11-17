package my.fumui.sairitsu.gameplay

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App-wide gameplay settings. Defaults: BGM volume 100 %, effect volume ("SFX") 25 % and no offsets (level and
 * server offsets would be other layers; this is the device's). Offsets are seconds.
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

    private fun optionPrefix(engine: String) = "option|$engine|"

    private companion object {
        val SAVE_LEVELS_I_PLAY = booleanPreferencesKey("save_levels_i_play")
        val SHOW_FPS = booleanPreferencesKey("show_fps")
        val EFFECT_VOLUME = floatPreferencesKey("effect_volume")
        val BGM_VOLUME = floatPreferencesKey("bgm_volume")
        val AUDIO_OFFSET = floatPreferencesKey("audio_offset")
        val INPUT_OFFSET = floatPreferencesKey("input_offset")
    }
}
