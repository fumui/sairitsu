package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.model.OptionValue
import my.fumui.sairitsu.data.model.SearchOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * One Level Option of an engine, from `engine.configuration` (`options[i]`). The configuration's order is the Level
 * Option block's order: option i is entry i of the block the runtime receives. [title] and [values] may be
 * `##LOCALIZE:` text (see Localizer); an option without a [title] shows its [name].
 */
data class EngineOption(
    val name: String,
    val type: String, // "slider", "toggle" or "select"
    val def: Float,
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    val values: List<String> = emptyList(),
    val title: String? = null,
    val description: String? = null,
    val scope: String? = null,
    val category: String? = null,
    val advanced: Boolean = false,
    val unit: String? = null,
)

object EngineOptions {
    /** The configuration text of a file that is gzip (the repository's form) or plain JSON. */
    fun decode(bytes: ByteArray): String {
        val gzip = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val raw = if (gzip) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
        return raw.toString(Charsets.UTF_8)
    }

    /** The options of an engine configuration, in order. Entries of an unknown type are kept as toggles. */
    fun parse(json: String): List<EngineOption> {
        val options = Json.parseToJsonElement(json).jsonObject["options"]?.jsonArray ?: JsonArray(emptyList())
        return options.map { element ->
            val o = element.jsonObject
            EngineOption(
                name = o.str("name") ?: "",
                type = o.str("type") ?: "toggle",
                def = o.num("def") ?: 0f,
                min = o.num("min"),
                max = o.num("max"),
                step = o.num("step"),
                values = (o["values"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" } ?: emptyList(),
                title = o.str("title"),
                description = o.str("description"),
                scope = o.str("scope"),
                category = o.str("category"),
                advanced = (o["advanced"] as? JsonPrimitive)?.booleanOrNull ?: false,
                unit = o.str("unit"),
            )
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull
}

/** The option that sets the playback speed: Watch plays at 1 whatever is saved. */
const val SPEED_OPTION = "#SPEED"

/**
 * The Level Option block for the runtime: one value per option in the configuration's order, the saved value or the
 * default. In Watch `#SPEED` is forced to 1.
 */
fun optionBlock(options: List<EngineOption>, saved: Map<String, Float>, watch: Boolean = true): FloatArray =
    FloatArray(options.size) { i ->
        val o = options[i]
        if (watch && o.name == SPEED_OPTION) 1f else saved[o.name] ?: o.def
    }

/** The option as a form field, so `OptionControl` renders it. A select's choices are named by their index. */
fun EngineOption.toSearchOption(): SearchOption = SearchOption(
    query = name,
    name = title ?: name,
    type = type,
    def = when (type) {
        "toggle" -> JsonPrimitive(def != 0f)
        "select" -> JsonPrimitive(def.toInt().toString())
        else -> JsonPrimitive(def.decimal())
    },
    values = values.mapIndexed { i, v -> OptionValue(i.toString(), v) },
    min = min?.decimal(),
    max = max?.decimal(),
    step = step?.decimal(),
)

/** The float as the decimal it was written as (9.8f -> 9.8, not 9.800000190734863), for display. */
private fun Float.decimal(): Double = toString().toDouble()

/** The form state for a stored [value] of this option. */
fun EngineOption.toState(value: Float): OptionState = when (type) {
    "toggle" -> OptionState.Toggle(value != 0f)
    "select" -> OptionState.Select(value.toInt().toString())
    else -> OptionState.Slider(value.decimal())
}

/** The float a form state stores; null for a state an engine option never has. */
fun OptionState.toStoredFloat(): Float? = when (this) {
    is OptionState.Toggle -> if (on) 1f else 0f
    is OptionState.Select -> name.toFloatOrNull()
    is OptionState.Slider -> value.toFloat()
    else -> null
}

/**
 * A replay `config` file (gzip JSON `{options, optionNames}`): the user's Level Option values in the
 * configuration's order, with their names. Used by the debug import.
 */
class ReplayConfig(val names: List<String>, val values: FloatArray) {
    fun byName(): Map<String, Float> = names.indices.associate { names[it] to values[it] }

    companion object {
        fun parse(json: String): ReplayConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            val names = root["optionNames"]!!.jsonArray.map { it.jsonPrimitive().content }
            val values = root["options"]!!.jsonArray.map { it.jsonPrimitive().content.toFloat() }.toFloatArray()
            require(names.size == values.size) { "replay config: ${names.size} names, ${values.size} values" }
            return ReplayConfig(names, values)
        }

        private fun kotlinx.serialization.json.JsonElement.jsonPrimitive(): JsonPrimitive = this as JsonPrimitive
    }
}
