package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.Srl
import my.fumui.sairitsu.data.model.ServerJson
import my.fumui.sairitsu.gameplay.LoadedResources
import my.fumui.sairitsu.gameplay.LocalLevel
import my.fumui.sairitsu.gameplay.ResourceCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** An entry names cache keys whose files are gone (evicted or deleted while it was being downloaded). */
class MissingFiles(val keys: Set<String>) : IOException("Missing cached files: $keys")

data class StorageInfo(val levels: Int, val libraryBytes: Long, val cacheBytes: Long)

/**
 * The downloaded levels: the [index] plus the [cache] files it references. Adding and deleting share one lock, so a
 * delete cannot release a file between an add's check and its write.
 */
class Library(
    val index: LibraryIndex,
    private val cache: ResourceCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Adds (or replaces) [entry]; throws [MissingFiles] when any of its files is not cached. */
    suspend fun add(entry: LibraryEntry) = mutex.withLock {
        val missing = entry.allKeys().filterTo(HashSet()) { !cache.has(it) }
        if (missing.isNotEmpty()) throw MissingFiles(missing)
        val released = index.put(entry)
        withContext(Dispatchers.IO) { cache.delete(released) }
    }

    /** Removes the level and deletes its files that no other downloaded level uses. */
    suspend fun delete(key: LevelKey) = mutex.withLock {
        val released = index.remove(key)
        withContext(Dispatchers.IO) { cache.delete(released) }
    }

    /** The downloaded level's keys for [my.fumui.sairitsu.gameplay.LevelLoader]; null when not downloaded. */
    fun local(key: LevelKey): LocalLevel? = index.get(key)?.let { LocalLevel(it.detailsKey, it.resourceKeys) }

    /** Library size (unique files) and the rest of the cache (streamed levels). */
    suspend fun storage(): StorageInfo = withContext(Dispatchers.IO) {
        val libraryBytes = cache.sizeOf(index.pinnedKeys())
        StorageInfo(index.entries.value.size, libraryBytes, (cache.totalBytes() - libraryBytes).coerceAtLeast(0))
    }

    /** The saved details of [entry]; null when the file is missing or unreadable. */
    suspend fun readDetails(entry: LibraryEntry): ItemDetails? = withContext(Dispatchers.IO) {
        try {
            cache.file(entry.detailsKey)?.let { readDetails(it.readText(Charsets.UTF_8)) }
        } catch (e: Exception) {
            null
        }
    }

    /** The index entry for a level [loaded] from [server]; reads the cached details for what the Play list shows. */
    fun entryFrom(
        server: ServerAddress,
        serverTitle: String,
        loaded: LoadedResources,
        coverKey: String?,
        previewKey: String?,
    ): LibraryEntry {
        val file = cache.file(loaded.detailsKey) ?: throw MissingFiles(setOf(loaded.detailsKey))
        val item = readDetails(file.readText(Charsets.UTF_8)).item
        return LibraryEntry(
            server = server.linkAddress,
            serverTitle = serverTitle,
            name = item.name,
            title = item.title,
            artists = item.artists,
            author = item.author,
            rating = item.rating,
            tags = item.tags.map { it.title }.filter { it.isNotBlank() },
            engineName = item.engine?.name,
            engineTitle = item.engine?.title,
            addedAt = now(),
            resourceKeys = loaded.keys,
            detailsKey = loaded.detailsKey,
            coverKey = coverKey,
            previewKey = previewKey,
            serverScheme = server.baseUrl.scheme,
        )
    }

    /** The details a level was loaded with (for the cover and preview to fetch). */
    fun detailsOf(loaded: LoadedResources): ItemDetails {
        val file = cache.file(loaded.detailsKey) ?: throw MissingFiles(setOf(loaded.detailsKey))
        return readDetails(file.readText(Charsets.UTF_8))
    }

    /**
     * The level that was [loaded] from [server], for the result screen. Its cover URL is absolute: the cached file of a
     * downloaded level, otherwise the server's. Null when the details are missing or unreadable.
     */
    suspend fun playedItem(server: ServerAddress, loaded: LoadedResources): LevelItem? = withContext(Dispatchers.IO) {
        try {
            val item = detailsOf(loaded).item
            val local = index.get(LevelKey(server.linkAddress, item.name))?.coverKey?.let(cache::file)
            val cover = local?.toURI()?.toString() ?: server.resolve(item.cover?.url)
            item.copy(cover = cover?.let { Srl(it) })
        } catch (e: Exception) {
            null
        }
    }

    private fun readDetails(text: String): ItemDetails = ServerJson.decodeFromString(ItemDetails.serializer(), text)
}
