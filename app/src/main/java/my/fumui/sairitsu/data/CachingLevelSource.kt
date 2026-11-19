package my.fumui.sairitsu.data

import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.data.model.ItemInfo
import my.fumui.sairitsu.data.model.ItemList
import my.fumui.sairitsu.data.model.ServerInfo

/**
 * Recent server responses, shared by every browser screen for the app's lifetime, so reopening a
 * server or reselecting a level doesn't wait on the network. Entries expire after [ttlMillis];
 * at most [maxEntries] are kept, least recently used dropped first. Failures are never stored.
 */
class ResponseCache(
    private val ttlMillis: Long = 5 * 60_000L,
    private val maxEntries: Int = 400,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val value: Any, val at: Long)

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > maxEntries
    }

    @Synchronized
    fun get(key: String): Any? {
        val entry = entries[key] ?: return null
        if (now() - entry.at > ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: String, value: Any) {
        entries[key] = Entry(value, now())
    }

    /** Drops everything cached for [server] (keys starting with "$server|"). */
    @Synchronized
    fun invalidate(server: String) {
        entries.keys.removeAll { it.startsWith("$server|") }
    }

    /** The cached value for [key], or [load]'s result, cached. */
    suspend fun <T : Any> getOrLoad(key: String, load: suspend () -> T): T {
        @Suppress("UNCHECKED_CAST")
        (get(key) as T?)?.let { return it }
        return load().also { put(key, it) }
    }
}

/** [inner] (the server at [server]) with its responses kept in [cache]. */
class CachingLevelSource(
    private val inner: LevelSource,
    private val cache: ResponseCache,
    private val server: String,
) : LevelSource {
    override suspend fun serverInfo(): ServerInfo = cache.getOrLoad("$server|info") { inner.serverInfo() }

    override suspend fun levelInfo(): ItemInfo = cache.getOrLoad("$server|levels/info") { inner.levelInfo() }

    override suspend fun levelList(params: Map<String, String>, cursor: String?): ItemList {
        val query = params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        return cache.getOrLoad("$server|levels/list?$query|$cursor") { inner.levelList(params, cursor) }
    }

    override suspend fun levelDetails(name: String): ItemDetails =
        cache.getOrLoad("$server|levels/$name") { inner.levelDetails(name) }
}
