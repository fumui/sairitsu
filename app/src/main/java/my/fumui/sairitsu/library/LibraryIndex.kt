package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The downloaded levels, kept in [file] as a JSON list and in memory. Writes replace the file atomically (a temp file
 * renamed over it), so a write cut short leaves the previous index. A file that does not parse is renamed to
 * `<name>.broken-<epoch ms>` and the index starts empty ([recoveredFromCorruption]).
 */
class LibraryIndex(private val file: File) {
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val entries: StateFlow<List<LibraryEntry>> = _entries.asStateFlow()

    var recoveredFromCorruption: Boolean = false
        private set

    /** The user was told about the reset; don't tell them again. */
    fun acknowledgeRecovery() {
        recoveredFromCorruption = false
    }

    init {
        if (file.isFile) {
            try {
                _entries.value = JSON.decodeFromString(SERIALIZER, file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                file.renameTo(File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}"))
                recoveredFromCorruption = true
            }
        }
    }

    fun get(key: LevelKey): LibraryEntry? = _entries.value.firstOrNull { it.key == key }

    /** Every cache key some downloaded level uses. */
    fun pinnedKeys(): Set<String> = _entries.value.flatMapTo(HashSet()) { it.allKeys() }

    /**
     * Adds [entry], replacing the one with the same key (whose `addedAt` is kept). Returns the replaced entry's keys
     * that no entry references any more.
     */
    suspend fun put(entry: LibraryEntry): Set<String> = mutex.withLock {
        val current = _entries.value
        val old = current.firstOrNull { it.key == entry.key }
        val stored = if (old != null) entry.copy(addedAt = old.addedAt) else entry
        val next = if (old != null) current.map { if (it.key == entry.key) stored else it } else current + stored
        write(next)
        old?.allKeys().orEmpty() - next.flatMapTo(HashSet()) { it.allKeys() }
    }

    /** Removes the level [key]; returns its keys that no remaining entry references (empty when absent). */
    suspend fun remove(key: LevelKey): Set<String> = mutex.withLock {
        val current = _entries.value
        val old = current.firstOrNull { it.key == key } ?: return@withLock emptySet()
        val next = current - old
        write(next)
        old.allKeys() - next.flatMapTo(HashSet()) { it.allKeys() }
    }

    /** Removes the levels [keys] in one write; returns their keys that no remaining entry references. */
    suspend fun removeAll(keys: Set<LevelKey>): Set<String> = mutex.withLock {
        val current = _entries.value
        val (old, next) = current.partition { it.key in keys }
        if (old.isEmpty()) return@withLock emptySet()
        write(next)
        old.flatMapTo(HashSet()) { it.allKeys() } - next.flatMapTo(HashSet()) { it.allKeys() }
    }

    private suspend fun write(next: List<LibraryEntry>) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(JSON.encodeToString(SERIALIZER, next), Charsets.UTF_8)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        _entries.value = next
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val SERIALIZER = ListSerializer(LibraryEntry.serializer())
    }
}
