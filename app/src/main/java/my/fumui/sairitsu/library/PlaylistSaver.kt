package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.LevelItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How much of a playlist is downloaded, as its row shows it. */
enum class PlaylistRowState { NoneSaved, PartlySaved, Downloading, AllSaved }

data class PlaylistProgress(val saved: Int, val total: Int, val state: PlaylistRowState)

/** [levels] of [server] counted against the library ([libraryKeys]) and the running downloads ([active]). */
fun playlistProgress(server: String, levels: List<LevelItem>, libraryKeys: Set<LevelKey>, active: Set<LevelKey>): PlaylistProgress {
    val keys = levels.map { LevelKey(server, it.name) }
    val saved = keys.count { it in libraryKeys }
    val state = when {
        keys.isEmpty() -> PlaylistRowState.NoneSaved
        keys.any { it in active } -> PlaylistRowState.Downloading
        saved == keys.size -> PlaylistRowState.AllSaved
        saved > 0 -> PlaylistRowState.PartlySaved
        else -> PlaylistRowState.NoneSaved
    }
    return PlaylistProgress(saved, keys.size, state)
}

/** The collection a playlist goes into: its (localized) [title], or its [name] when the title can't name a collection. */
fun playlistCollectionName(title: String, name: String): String =
    if (collectionNameError(title, emptyList()) == null) title.trim() else name

/** Downloads a playlist into a collection: downloaded levels join now, the rest when their download finishes. */
class PlaylistSaver(
    private val collections: CollectionStore,
    private val download: (server: ServerAddress, serverTitle: String, level: LevelItem) -> Unit,
    private val libraryKeys: () -> Set<LevelKey>,
) {
    private val mutex = Mutex()

    /**
     * Reuses the collection named [collectionName] (ignoring case) or creates it; returns its id, or the failure when the
     * collections can't be saved (a full disk).
     */
    suspend fun save(server: ServerAddress, serverTitle: String, collectionName: String, levels: List<LevelItem>): Result<String> =
        try {
            Result.success(saveOrThrow(server, serverTitle, collectionName, levels))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun saveOrThrow(server: ServerAddress, serverTitle: String, collectionName: String, levels: List<LevelItem>): String =
        mutex.withLock {
            val id = collections.collections.value.firstOrNull { it.name.equals(collectionName.trim(), ignoreCase = true) }?.id
                ?: collections.create(collectionName).id
            val library = libraryKeys()
            val ready = HashSet<LevelKey>()
            for (level in levels) {
                val key = LevelKey(server.linkAddress, level.name)
                if (key in library) {
                    ready += key
                } else {
                    // Queue first: the pending watcher keeps an entry only while its level is downloading.
                    download(server, serverTitle, level)
                    collections.addPending(key, id)
                }
            }
            if (ready.isNotEmpty()) collections.add(setOf(id), ready)
            id
        }
}
