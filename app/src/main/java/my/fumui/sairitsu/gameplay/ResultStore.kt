package my.fumui.sairitsu.gameplay

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.library.LevelStats
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The best Play result per (server, level), kept on the device. */
interface ResultStore {
    suspend fun best(server: ServerAddress, level: String): PlayResult?

    /**
     * Saves [result] when it is better than the stored best (the first result always is); true when it became the best.
     * Higher arcade score wins; a tie goes to the higher accuracy score; a fail never replaces a pass (or better).
     */
    suspend fun record(server: ServerAddress, level: String, result: PlayResult): Boolean

    /** Notes that the level was played or watched at [at] (epoch ms). */
    suspend fun markPlayed(server: ServerAddress, level: String, at: Long) {}

    /** The best result and last-played time of every level the device knows of. */
    suspend fun stats(): Map<LevelKey, LevelStats> = emptyMap()
}

/** Whether [new] replaces [old] as the best. */
internal fun isBetter(new: PlayResult, old: PlayResult?): Boolean = when {
    old == null -> true
    new.grade == Grade.Fail && old.grade != Grade.Fail -> false
    new.arcadeScore != old.arcadeScore -> new.arcadeScore > old.arcadeScore
    else -> new.accuracyScore > old.accuracyScore
}

/** In the app's gameplay DataStore (the one [SettingsStore] uses), under `best:<server>/<level>` as JSON. */
class DataStoreResultStore(private val dataStore: DataStore<Preferences>) : ResultStore {
    constructor(context: Context) : this(context.applicationContext.gameplayStore)

    private fun key(server: ServerAddress, level: String) = stringPreferencesKey("$BEST${server.linkAddress}/$level")

    private fun playedKey(server: ServerAddress, level: String) = longPreferencesKey("$PLAYED${server.linkAddress}/$level")

    override suspend fun best(server: ServerAddress, level: String): PlayResult? =
        dataStore.data.first()[key(server, level)]?.let(::decode)

    override suspend fun markPlayed(server: ServerAddress, level: String, at: Long) {
        dataStore.edit { it[playedKey(server, level)] = at }
    }

    override suspend fun stats(): Map<LevelKey, LevelStats> {
        val out = HashMap<LevelKey, LevelStats>()
        for ((key, value) in dataStore.data.first().asMap()) {
            val name = key.name
            when {
                name.startsWith(BEST) && value is String -> levelKey(name.removePrefix(BEST))?.let { k ->
                    out[k] = (out[k] ?: LevelStats()).copy(best = decode(value))
                }
                name.startsWith(PLAYED) && value is Long -> levelKey(name.removePrefix(PLAYED))?.let { k ->
                    out[k] = (out[k] ?: LevelStats()).copy(lastPlayed = value)
                }
            }
        }
        return out
    }

    /** `<linkAddress>/<level>`: level names have no `/`, server addresses may. */
    private fun levelKey(rest: String): LevelKey? {
        val slash = rest.lastIndexOf('/')
        if (slash <= 0 || slash == rest.length - 1) return null
        return LevelKey(rest.substring(0, slash), rest.substring(slash + 1))
    }

    private fun decode(text: String): PlayResult? = try {
        Json.decodeFromString<PlayResult>(text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val BEST = "best:"
        const val PLAYED = "lastPlayed:"
    }

    override suspend fun record(server: ServerAddress, level: String, result: PlayResult): Boolean {
        var better = false
        val key = key(server, level)
        dataStore.edit { prefs ->
            val old = prefs[key]?.let {
                try {
                    Json.decodeFromString<PlayResult>(it)
                } catch (e: SerializationException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            better = isBetter(result, old)
            if (better) prefs[key] = Json.encodeToString(PlayResult.serializer(), result)
        }
        return better
    }
}
