package my.fumui.sairitsu.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import my.fumui.sairitsu.data.appSettings
import my.fumui.sairitsu.data.model.EngineRef
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.Srl
import my.fumui.sairitsu.data.model.Tag
import my.fumui.sairitsu.gameplay.ResourceKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

/** The Play list's last search, filters and sort, so the list looks the same when the user comes back. */
class LibraryQueryStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.appSettings)

    /** The saved query; the default when none is saved or it is unreadable. */
    val query: Flow<LibraryQuery> = store.data.map { prefs ->
        prefs[KEY]?.let { runCatching { JSON.decodeFromString(LibraryQuery.serializer(), it) }.getOrNull() }
            ?: LibraryQuery()
    }

    suspend fun save(query: LibraryQuery) {
        store.edit { it[KEY] = JSON.encodeToString(LibraryQuery.serializer(), query) }
    }

    private companion object {
        val KEY = stringPreferencesKey("library_query")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The entry as the picker's list and details pane show it. Cover, preview and BGM point at the cached files
 * (`file:` URIs, which [my.fumui.sairitsu.data.ServerAddress.resolve] leaves alone), so nothing needs the network.
 */
fun LibraryEntry.toLevelItem(cache: (String) -> File?): LevelItem {
    fun srl(key: String?) = key?.let(cache)?.let { Srl(it.fileUri()) }
    return LevelItem(
        name = name,
        title = title,
        rating = rating,
        artists = artists,
        author = author,
        tags = tags.map { Tag(it) },
        engine = engineName?.let { EngineRef(it, engineTitle ?: it) },
        cover = srl(coverKey),
        bgm = srl(resourceKeys[ResourceKind.LEVEL_BGM]),
        preview = srl(previewKey),
    )
}

/**
 * The `file:` URI of a regular file, as [File.toURI] gives it but with no disk access: toURI stats the path to see
 * whether it's a directory, which costs a lot when every row of a large list builds a few of these on the main thread.
 */
fun File.fileUri(): String {
    val path = absolutePath.replace(File.separatorChar, '/')
    return URI("file", null, if (path.startsWith("/")) path else "/$path", null).toString()
}
