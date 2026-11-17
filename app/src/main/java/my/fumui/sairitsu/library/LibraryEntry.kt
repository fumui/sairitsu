package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.gameplay.ResourceKind
import kotlinx.serialization.Serializable

/**
 * A downloaded level: what the Play list shows, searches and sorts by, plus the [my.fumui.sairitsu.gameplay.ResourceCache]
 * keys of every file it needs. [server] is the server's linkAddress; [tags] are raw tag titles (localized for display).
 */
@Serializable
data class LibraryEntry(
    val server: String,
    val serverTitle: String,
    val name: String,
    val title: String,
    val artists: String,
    val author: String,
    val rating: Double,
    val tags: List<String>,
    val engineName: String?,
    val engineTitle: String?,
    val addedAt: Long,
    val resourceKeys: Map<ResourceKind, String>,
    val detailsKey: String,
    val coverKey: String?,
    val previewKey: String?,
    /** The server's scheme: [server] (a linkAddress) has none, and an http server must not become https. */
    val serverScheme: String = "https",
) {
    val key: LevelKey get() = LevelKey(server, name)

    /** The server address to launch with ([my.fumui.sairitsu.data.ServerAddress.parse] keeps the scheme). */
    val serverUrl: String get() = "$serverScheme://$server"

    /** Every cache key this level uses. */
    fun allKeys(): Set<String> = buildSet {
        addAll(resourceKeys.values)
        add(detailsKey)
        coverKey?.let(::add)
        previewKey?.let(::add)
    }
}
