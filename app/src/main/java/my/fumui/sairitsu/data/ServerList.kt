package my.fumui.sairitsu.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The app's `settings` preferences: the server list. */
internal val Context.appSettings by preferencesDataStore(name = "settings")

/** A server in the user's list; [address] is its [ServerAddress.linkAddress], [scheme] how to reach it. */
@Serializable
data class SavedServer(val address: String, val title: String, val scheme: String = "https") {
    /** What [ServerAddress.parse] turns back into this server (an http server stays http). */
    val url: String get() = "$scheme://$address"
}

private fun ServerAddress.toSaved(title: String?) =
    SavedServer(linkAddress, title?.takeIf { it.isNotBlank() } ?: linkAddress, baseUrl.scheme)

/** The servers the user added, in the order added, as JSON in the `settings` preferences. Empty on first run: the
 * app ships with no server, so it only ever talks to servers the user chose. */
class ServerListStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.appSettings)

    sealed interface AddResult {
        data class Added(val server: SavedServer) : AddResult

        data object Duplicate : AddResult

        data object Invalid : AddResult
    }

    val servers: Flow<List<SavedServer>> = store.data.map(::current)

    /** Adds the server at [input] (any spelling [ServerAddress.parse] accepts) titled [title], or its address. */
    suspend fun add(input: String, title: String?): AddResult {
        val parsed = ServerAddress.parse(input) ?: return AddResult.Invalid
        var result: AddResult = AddResult.Duplicate
        store.edit { prefs ->
            val list = current(prefs)
            if (list.none { it.address == parsed.linkAddress }) {
                val server = parsed.toSaved(title)
                prefs[SERVERS] = encode(list + server)
                result = AddResult.Added(server)
            }
        }
        return result
    }

    suspend fun remove(address: String) {
        store.edit { prefs -> prefs[SERVERS] = encode(current(prefs).filter { it.address != address }) }
    }

    /**
     * Replaces the server at [address] in place with the one at [input] titled [title] (its address when blank).
     * Duplicate when [input] is another server already in the list; Invalid when it doesn't parse.
     */
    suspend fun update(address: String, input: String, title: String): AddResult {
        val parsed = ServerAddress.parse(input) ?: return AddResult.Invalid
        var result: AddResult = AddResult.Duplicate
        store.edit { prefs ->
            val list = current(prefs)
            if (list.none { it.address == parsed.linkAddress && it.address != address }) {
                val server = parsed.toSaved(title)
                prefs[SERVERS] = encode(list.map { if (it.address == address) server else it })
                result = AddResult.Added(server)
            }
        }
        return result
    }

    suspend fun setTitle(address: String, title: String) {
        store.edit { prefs ->
            prefs[SERVERS] = encode(current(prefs).map { if (it.address == address) it.copy(title = title) else it })
        }
    }

    private fun current(prefs: Preferences): List<SavedServer> =
        prefs[SERVERS]?.let { text -> runCatching { JSON.decodeFromString(SERIALIZER, text) }.getOrNull() }
            ?: emptyList()

    private fun encode(list: List<SavedServer>) = JSON.encodeToString(SERIALIZER, list)

    private companion object {
        val SERVERS = stringPreferencesKey("servers")
        val JSON = Json { ignoreUnknownKeys = true }
        val SERIALIZER = ListSerializer(SavedServer.serializer())
    }
}
