package my.fumui.sairitsu.gameplay

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.GZIPInputStream

/** Saved replays in [dir]: `<uuid>.json.gz` (the replay) next to `<uuid>.config.gz` (the run's options). */
class ReplayStore(private val dir: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** Copies [replay] and [config] under a new id and returns it. Throws [IOException]; a failed save leaves no files. */
    suspend fun save(replay: File, config: File): String = withContext(io) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("can't create $dir")
        val id = UUID.randomUUID().toString()
        val targets = listOf(replayFile(id), configFile(id))
        val parts = targets.map { File(dir, it.name + ".part") }
        try {
            replay.copyTo(parts[0], overwrite = true)
            config.copyTo(parts[1], overwrite = true)
            for ((part, target) in parts.zip(targets)) {
                if (!part.renameTo(target)) throw IOException("can't rename $part")
            }
        } catch (e: Exception) {
            (parts + targets).forEach { it.delete() }
            throw e as? IOException ?: IOException(e)
        }
        id
    }

    fun replayFile(id: String): File = File(dir, "${checked(id)}.json.gz")

    fun configFile(id: String): File = File(dir, "${checked(id)}.config.gz")

    /** The `options` of the saved config. Throws [IOException] when it is missing or unreadable. */
    suspend fun options(id: String): FloatArray = withContext(io) {
        try {
            // Wrap inside use so a bad gzip header (thrown by the constructor) still closes the file.
            val text = configFile(id).inputStream().use { GZIPInputStream(it).readBytes() }.decodeToString()
            val array = Json.parseToJsonElement(text).jsonObject["options"]?.jsonArray
                ?: throw IOException("config has no options")
            FloatArray(array.size) { array[it].jsonPrimitive.float }
        } catch (e: SerializationException) {
            throw IOException(e)
        } catch (e: IllegalArgumentException) {
            throw IOException(e)
        } catch (e: IllegalStateException) {
            throw IOException(e)
        }
    }

    private fun checked(id: String): String {
        val parsed = try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("not a replay id: $id")
        }
        // fromString accepts short forms such as "1-1-1-1-1"; only the canonical form is a file name we made.
        require(parsed.toString() == id) { "not a replay id: $id" }
        return id
    }
}

/** The engine's three resource keys joined by newlines, or null when any is missing. */
fun engineKey(keys: Map<ResourceKind, String>): String? {
    val parts = listOf(ResourceKind.ENGINE_PLAY_DATA, ResourceKind.ENGINE_WATCH_DATA, ResourceKind.ENGINE_CONFIGURATION)
        .map { keys[it] ?: return null }
    return parts.joinToString("\n")
}

/** How a saved replay relates to the level as it is now. */
enum class ReplayMatch { Same, EngineChanged, ChartChanged }

fun replayMatch(levelData: String?, engine: String?, keys: Map<ResourceKind, String>): ReplayMatch = when {
    levelData == null || levelData != keys[ResourceKind.LEVEL_DATA] -> ReplayMatch.ChartChanged
    engine == null || engine != engineKey(keys) -> ReplayMatch.EngineChanged
    else -> ReplayMatch.Same
}
