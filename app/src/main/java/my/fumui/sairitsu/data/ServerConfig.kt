package my.fumui.sairitsu.data

import my.fumui.sairitsu.data.model.SearchOption
import my.fumui.sairitsu.data.model.ServerJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Turns a server's configuration options to and from the query parameters stored and sent. */
object ServerConfigCodec {
    fun encode(options: List<SearchOption>, values: List<OptionState>): Map<String, String> =
        SearchForms.params(options, values)

    /** Stored [params] back into states; missing or undecodable values (and unsupported types) give the default. */
    fun decode(options: List<SearchOption>, params: Map<String, String>): List<OptionState> =
        options.map { option ->
            val default = SearchForms.defaultOf(option)
            val raw = params[option.query] ?: return@map default
            decodeOne(option, raw) ?: default
        }

    private fun decodeOne(option: SearchOption, raw: String): OptionState? = when (option.type) {
        "text", "textArea" -> OptionState.Text(raw)
        "toggle" -> when (raw) {
            "1" -> OptionState.Toggle(true)
            "0" -> OptionState.Toggle(false)
            else -> null
        }
        "select" -> raw.takeIf { name -> option.values.any { it.name == name } }?.let(OptionState::Select)
        "multi" -> {
            val chosen = raw.split(',').filter { it.isNotEmpty() }
            val names = option.values.map { it.name }.toSet()
            if (chosen.any { it !in names }) null else OptionState.Multi(option.values.map { it.name in chosen })
        }
        "slider" -> {
            val min = option.min
            val max = option.max
            raw.toDoubleOrNull()?.takeIf { min != null && max != null && it in min..max }?.let(OptionState::Slider)
        }
        else -> null
    }
}

/** Per-server configuration values, kept in one JSON file. */
class ServerConfigStore(private val file: File) {
    private val mutex = Mutex()
    private val _values = MutableStateFlow(read())

    /** linkAddress → query → value. Loaded in the constructor; corrupt or missing file → empty. */
    val values: StateFlow<Map<String, Map<String, String>>> = _values.asStateFlow()

    suspend fun save(server: String, params: Map<String, String>) =
        update { if (params.isEmpty()) it - server else it + (server to params) }

    suspend fun clear(server: String) = update { it - server }

    suspend fun move(from: String, to: String) = update { all ->
        val moved = all[from] ?: return@update all
        (all - from) + (to to moved)
    }

    private suspend fun update(change: (Map<String, Map<String, String>>) -> Map<String, Map<String, String>>) =
        mutex.withLock {
            val next = change(_values.value)
            if (next == _values.value) return@withLock
            withContext(Dispatchers.IO) { commit(next) }
            _values.value = next
        }

    private fun commit(next: Map<String, Map<String, String>>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(ServerJson.encodeToString(SERIALIZER, next), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun read(): Map<String, Map<String, String>> = try {
        if (file.isFile) ServerJson.decodeFromString(SERIALIZER, file.readText(Charsets.UTF_8)) else emptyMap()
    } catch (e: SerializationException) {
        emptyMap()
    } catch (e: IllegalArgumentException) {
        emptyMap()
    } catch (e: IOException) {
        emptyMap()
    }

    private companion object {
        val SERIALIZER = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))
    }
}
