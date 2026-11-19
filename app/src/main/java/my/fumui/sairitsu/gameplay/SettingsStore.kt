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
import kotlinx.coroutines.flow.first
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

    /** The [kind] item chosen for [level] on [server] (a [my.fumui.sairitsu.data.ServerAddress.linkAddress]). */
    fun levelItem(kind: ItemKind, server: String, level: String): Flow<ItemSelection> =
        store.data.map { decodeItem(it[levelItemKey(kind, server, level)], ItemSelection.UseEngine) }

    /** The [kind] item chosen for every level of [engine]. */
    fun engineItem(kind: ItemKind, engine: String): Flow<ItemSelection> =
        store.data.map { decodeItem(it[engineItemKey(kind, engine)], ItemSelection.Original) }

    /** Saves the level's choice. Following the engine is the default, so it leaves nothing behind. */
    suspend fun saveLevelItem(kind: ItemKind, server: String, level: String, selection: ItemSelection) {
        store.edit { prefs ->
            val key = levelItemKey(kind, server, level)
            if (selection == ItemSelection.UseEngine) prefs.remove(key) else prefs[key] = encodeItem(selection)
        }
    }

    /** Saves the engine's choice. The original is the default, so it leaves nothing behind. */
    suspend fun saveEngineItem(kind: ItemKind, engine: String, selection: ItemSelection) {
        store.edit { prefs ->
            val key = engineItemKey(kind, engine)
            if (selection is ItemSelection.Saved) prefs[key] = encodeItem(selection) else prefs.remove(key)
        }
    }

    /** Clears every level and engine choice, of every kind, that names the saved item [id]. */
    suspend fun clearItem(id: String) {
        val value = encodeItem(ItemSelection.Saved(id))
        store.edit { prefs ->
            prefs.asMap().filter { (key, saved) -> saved == value && ItemKind.entries.any { key.name.startsWith(itemPrefix(it)) } }
                .keys.forEach { prefs.remove(stringPreferencesKey(it.name)) }
        }
    }

    /** Every stored level and engine skin choice (saved or original), for the one-time move to [ItemLibrary]. */
    suspend fun skinChoices(): List<Pair<SkinScope, ItemSelection>> {
        val prefix = itemPrefix(ItemKind.Skin)
        return store.data.first().asMap().mapNotNull { (key, value) ->
            val id = (value as? String)?.let { decodeItem(it, ItemSelection.UseEngine) }?.takeIf { it != ItemSelection.UseEngine }
                ?: return@mapNotNull null
            val rest = key.name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return@mapNotNull null
            when {
                rest.startsWith("level|") -> {
                    val (server, level) = rest.removePrefix("level|").split('|', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                    SkinScope.Level(server, level) to id
                }
                rest.startsWith("engine|") -> SkinScope.Engine(rest.removePrefix("engine|")) to id
                else -> null
            }
        }
    }

    /** Whether the saved skins were already moved to [ItemLibrary]. */
    val itemsMigrated: Flow<Boolean> = store.data.map { it[ITEMS_MIGRATED] ?: false }

    suspend fun markItemsMigrated() {
        store.edit { it[ITEMS_MIGRATED] = true }
    }

    private fun itemPrefix(kind: ItemKind) = "${kind.wireType}|"

    private fun levelItemKey(kind: ItemKind, server: String, level: String) =
        stringPreferencesKey("${itemPrefix(kind)}level|$server|$level")

    private fun engineItemKey(kind: ItemKind, engine: String) = stringPreferencesKey("${itemPrefix(kind)}engine|$engine")

    private fun encodeItem(selection: ItemSelection): String = when (selection) {
        is ItemSelection.Saved -> SAVED_ITEM + selection.id
        else -> ORIGINAL_ITEM
    }

    private fun decodeItem(value: String?, default: ItemSelection): ItemSelection = when {
        value == ORIGINAL_ITEM -> ItemSelection.Original
        value != null && value.startsWith(SAVED_ITEM) -> ItemSelection.Saved(value.removePrefix(SAVED_ITEM))
        else -> default
    }

    private fun optionPrefix(engine: String) = "option|$engine|"

    private fun serverOffsetKey(server: String) = floatPreferencesKey("input_offset|server|$server")

    private fun levelOffsetKey(server: String, level: String) = floatPreferencesKey("input_offset|level|$server|$level")

    private companion object {
        const val ORIGINAL_ITEM = "original"
        const val SAVED_ITEM = "saved:"
        val ITEMS_MIGRATED = booleanPreferencesKey("items_migrated")
        val SAVE_LEVELS_I_PLAY = booleanPreferencesKey("save_levels_i_play")
        val SHOW_FPS = booleanPreferencesKey("show_fps")
        val EFFECT_VOLUME = floatPreferencesKey("effect_volume")
        val BGM_VOLUME = floatPreferencesKey("bgm_volume")
        val AUDIO_OFFSET = floatPreferencesKey("audio_offset")
        val INPUT_OFFSET = floatPreferencesKey("input_offset")
    }
}
