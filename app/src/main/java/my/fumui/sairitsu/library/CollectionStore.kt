package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The user's collections, kept in [file] as a JSON list and in memory, written like [LibraryIndex] (atomic replace,
 * corrupt file renamed to `<name>.broken-<epoch ms>`). On load, members that [libraryKeys] no longer has are dropped.
 * Calls that change nothing don't write.
 */
class CollectionStore(private val file: File, libraryKeys: () -> Set<LevelKey>) {
    private val mutex = Mutex()
    private val _collections = MutableStateFlow<List<LevelCollection>>(emptyList())

    /** In creation order; sort with [sortedByName] for display. */
    val collections: StateFlow<List<LevelCollection>> = _collections.asStateFlow()

    var recoveredFromCorruption: Boolean = false
        private set

    /** The user was told about the reset; don't tell them again. */
    fun acknowledgeRecovery() {
        recoveredFromCorruption = false
    }

    init {
        val loaded = if (file.isFile) {
            try {
                JSON.decodeFromString(SERIALIZER, file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                file.renameTo(File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}"))
                recoveredFromCorruption = true
                null
            }
        } else {
            null
        }
        if (loaded != null) {
            val library = libraryKeys()
            val pruned = loaded.map { c -> c.copy(levels = c.levels.filter { it in library }) }
            if (pruned != loaded) {
                try {
                    writeFile(pruned)
                } catch (e: Exception) {
                    // E.g. the disk is full. The file keeps the stale members, which the next write drops.
                }
            }
            _collections.value = pruned
        }
    }

    fun get(id: String): LevelCollection? = _collections.value.firstOrNull { it.id == id }

    /** Adds a collection named [name] (trimmed; the caller validated it) holding [keys]. */
    suspend fun create(name: String, keys: Set<LevelKey> = emptySet(), now: Long = System.currentTimeMillis()): LevelCollection =
        mutex.withLock {
            val created = LevelCollection(UUID.randomUUID().toString(), name.trim(), now, keys.toList())
            write(_collections.value + created)
            created
        }

    suspend fun rename(id: String, name: String) = change { list ->
        list.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    suspend fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

    /** Adds [keys] to every collection in [ids] (unknown ids are ignored), after the members they already have. */
    suspend fun add(ids: Set<String>, keys: Set<LevelKey>) = change { list ->
        list.map { c -> if (c.id in ids) c.copy(levels = c.levels + keys.filterNot { it in c.levels }) else c }
    }

    suspend fun remove(ids: Set<String>, keys: Set<LevelKey>) = change { list ->
        list.map { c -> if (c.id in ids) c.copy(levels = c.levels.filterNot { it in keys }) else c }
    }

    /** Drops [key] from every collection (the level left the library). */
    suspend fun removeLevel(key: LevelKey) = removeLevels(setOf(key))

    /** Drops [keys] from every collection (the levels left the library). */
    suspend fun removeLevels(keys: Set<LevelKey>) =
        change { list -> list.map { c -> c.copy(levels = c.levels.filterNot { it in keys }) } }

    private val _pending = MutableStateFlow<Map<LevelKey, Set<String>>>(emptyMap())

    /**
     * Collections a level joins once its download finishes (ticked in the server browser before it was downloaded).
     * In memory only: lost with the process, like the download itself.
     */
    val pending: StateFlow<Map<LevelKey, Set<String>>> = _pending.asStateFlow()

    fun addPending(key: LevelKey, id: String) = _pending.update { it + (key to (it[key].orEmpty() + id)) }

    fun removePending(key: LevelKey, id: String) = _pending.update { current ->
        val left = current[key].orEmpty() - id
        if (left.isEmpty()) current - key else current + (key to left)
    }

    /**
     * Commits pending keys that are now in the library (one [add] per key; ids that no longer exist are skipped) and
     * drops keys that are neither in the library nor downloading (the download was cancelled). A failed download stays
     * in [activeDownloads], so its memberships wait for a retry.
     *
     * Both are read live, downloads first: the downloader writes the index before it clears a level's state, so a
     * level that left [activeDownloads] by the first read is in [libraryKeys] by the second, and a success never
     * looks like a cancel.
     */
    suspend fun settlePending(activeDownloads: () -> Set<LevelKey>, libraryKeys: () -> Set<LevelKey>) {
        if (_pending.value.isEmpty()) return
        val active = activeDownloads()
        val library = libraryKeys()
        for ((key, ids) in _pending.value) {
            when {
                key in library -> {
                    add(ids, setOf(key))
                    _pending.update { it - key }
                }
                key !in active -> _pending.update { it - key }
            }
        }
    }

    private suspend fun change(transform: (List<LevelCollection>) -> List<LevelCollection>) {
        mutex.withLock {
            val current = _collections.value
            val next = transform(current)
            if (next != current) write(next)
        }
    }

    private suspend fun write(next: List<LevelCollection>) {
        withContext(Dispatchers.IO) { writeFile(next) }
        _collections.value = next
    }

    private fun writeFile(next: List<LevelCollection>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(JSON.encodeToString(SERIALIZER, next), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val SERIALIZER = ListSerializer(LevelCollection.serializer())
    }
}
