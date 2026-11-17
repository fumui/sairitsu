package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.LevelKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One finished Play run of a level; [at] is epoch ms, null for the best kept from before history existed. */
@Serializable
data class RunRecord(
    val at: Long?,
    val result: PlayResult,
    /** Id of the saved replay in the ReplayStore, if one was saved. */
    val replay: String? = null,
    /** Resource key of the level data the run was played on. */
    val levelData: String? = null,
    /** The engine's resource keys, see [engineKey]. */
    val engine: String? = null,
)

/** How the detail pane orders a level's runs. */
enum class RunSort(val label: String) {
    Arcade("Arcade score"),
    Accuracy("Accuracy score"),
    Date("Date"),
}

/**
 * [runs] ordered by [sort]. Arcade: arcade, then accuracy, then newer first. Accuracy: accuracy, then arcade, then
 * newer first. Date: newest first (undated last), then arcade.
 */
fun sortRuns(runs: List<RunRecord>, sort: RunSort): List<RunRecord> {
    // Undated runs are the oldest.
    val time = { r: RunRecord -> r.at ?: Long.MIN_VALUE }
    val comparator = when (sort) {
        RunSort.Arcade -> compareByDescending<RunRecord> { it.result.arcadeScore }
            .thenByDescending { it.result.accuracyScore }.thenByDescending(time)
        RunSort.Accuracy -> compareByDescending<RunRecord> { it.result.accuracyScore }
            .thenByDescending { it.result.arcadeScore }.thenByDescending(time)
        RunSort.Date -> compareByDescending(time).thenByDescending { it.result.arcadeScore }
    }
    return runs.sortedWith(comparator)
}

/** Every finished Play run per level, kept on the device. */
interface RunHistory {
    /** The level's runs in file order; null when the level has no history file yet. */
    suspend fun runs(key: LevelKey): List<RunRecord>?

    /** Appends [record]; when the level has no history yet and [seed] is given, first writes `RunRecord(null, seed)`. */
    suspend fun append(key: LevelKey, record: RunRecord, seed: PlayResult? = null)

    /** Sets [replay] on the record with [at]; true when found and rewritten, false leaves the file as is. */
    suspend fun attachReplay(key: LevelKey, at: Long, replay: String): Boolean
}

/** One JSON line per run in `<dir>/<sha1 of server and level>.jsonl` (level names and addresses aren't safe file names). */
class FileRunHistory(private val dir: File, private val io: CoroutineDispatcher = Dispatchers.IO) : RunHistory {
    private val mutex = Mutex()

    // A newline, not '/', between the parts: addresses contain '/', so "a/b" + "c" would equal "a" + "b/c".
    private fun file(key: LevelKey) = File(dir, sha1Hex("${key.server}\n${key.level}".toByteArray()) + ".jsonl")

    override suspend fun runs(key: LevelKey): List<RunRecord>? = withContext(io) {
        mutex.withLock {
            val file = file(key)
            if (!file.isFile) return@withLock null
            file.readLines().filter { it.isNotBlank() }.mapNotNull(::decode)
        }
    }

    override suspend fun append(key: LevelKey, record: RunRecord, seed: PlayResult?) = withContext(io) {
        mutex.withLock {
            dir.mkdirs()
            val file = file(key)
            val text = buildString {
                val length = if (file.isFile) file.length() else 0L
                if (length == 0L) {
                    seed?.let { append(encode(RunRecord(null, it))) }
                } else if (lastByte(file) != '\n'.code) {
                    // A torn line (killed mid-append): start a new one so only that run is lost.
                    append('\n')
                }
                append(encode(record))
            }
            FileOutputStream(file, true).use { it.write(text.toByteArray()) }
        }
    }

    override suspend fun attachReplay(key: LevelKey, at: Long, replay: String): Boolean = withContext(io) {
        mutex.withLock {
            val file = file(key)
            if (!file.isFile) return@withLock false
            var found = false
            // Lines that don't decode (torn or foreign) are dropped from the rewrite, as reads already skip them.
            val records = file.readLines().filter { it.isNotBlank() }.mapNotNull(::decode).map {
                if (!found && it.at == at) {
                    found = true
                    it.copy(replay = replay)
                } else {
                    it
                }
            }
            if (!found) return@withLock false
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(records.joinToString("") { encode(it) })
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }
    }

    private fun lastByte(file: File): Int = RandomAccessFile(file, "r").use { raf ->
        raf.seek(raf.length() - 1)
        raf.read()
    }

    private fun encode(record: RunRecord) = Json.encodeToString(RunRecord.serializer(), record) + "\n"

    private fun decode(line: String): RunRecord? = try {
        Json.decodeFromString(RunRecord.serializer(), line)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
