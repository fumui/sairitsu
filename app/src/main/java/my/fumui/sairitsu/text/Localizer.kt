package my.fumui.sairitsu.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Resolves server text (engine-spec wiki, "Text Localization"): `##:` escapes, `##LOCALIZE:` maps,
 * `##TIME_RELATIVE:` stamps and `#KEY` tokens. Multiline text is resolved one line at a time.
 */
class Localizer(
    private val language: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun text(raw: String): String = if ('\n' in raw) raw.split('\n').joinToString("\n") { line(it) } else line(raw)

    private fun line(raw: String): String = when {
        raw.startsWith(ESCAPE) -> raw.removePrefix(ESCAPE)
        raw.startsWith(LOCALIZE) -> localize(raw.removePrefix(LOCALIZE))
        raw.startsWith(TIME_RELATIVE) -> relativeTime(raw.removePrefix(TIME_RELATIVE))
        KEY_PATTERN.matches(raw) -> raw.split(':').joinToString("") { key(it) }
        else -> raw
    }

    /** The map's text for [language], followed by any plain text the server put after the map. */
    private fun localize(body: String): String {
        val end = objectEnd(body) ?: return body
        val map = try {
            Json.parseToJsonElement(body.substring(0, end)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return body
        val value = map[language] ?: map["en"] ?: map.values.firstOrNull()
        val text = when (value) {
            null -> ""
            is JsonPrimitive -> value.content
            else -> value.toString()
        }
        return text + body.substring(end)
    }

    /** The index just past the JSON object [body] starts with, or null when it doesn't start with a complete one. */
    private fun objectEnd(body: String): Int? {
        if (!body.startsWith("{")) return null
        var depth = 0
        var inString = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> if (--depth == 0) return i + 1
            }
            i++
        }
        return null
    }

    private fun relativeTime(body: String): String {
        val time = body.toLongOrNull() ?: return body
        val seconds = (now() - time) / 1000
        if (seconds < 60) return "just now"
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days >= 365 -> ago(days / 365, "year")
            days >= 30 -> ago(days / 30, "month")
            days >= 1 -> ago(days, "day")
            hours >= 1 -> ago(hours, "hour")
            else -> ago(minutes, "minute")
        }
    }

    private fun ago(count: Long, unit: String) = if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"

    private fun key(part: String): String {
        if (!part.startsWith("#")) return part
        KEYS[part]?.let { return it }
        val words = part.removePrefix("#").replace('_', ' ').lowercase()
        return words.replaceFirstChar { it.titlecase() }
    }

    companion object {
        private const val ESCAPE = "##:"
        private const val LOCALIZE = "##LOCALIZE:"
        private const val TIME_RELATIVE = "##TIME_RELATIVE:"

        /** Server i18n keys such as `#LONG_VERSION` or `#A:#SEPARATOR_PLUS:#B`; user text like `#Hashtag` is left alone. */
        private val KEY_PATTERN = Regex("#[A-Z0-9_]+(:#[A-Z0-9_]+)*")

        /** Maps a device locale to the language codes servers use. */
        fun serverLanguage(locale: Locale): String {
            if (locale.language != "zh") return locale.language
            val simplified = locale.script == "Hans" || locale.country == "CN" || locale.country == "SG"
            return if (simplified) "zhs" else "zht"
        }

        private val KEYS = mapOf(
            "#SEPARATOR_PLUS" to " + ",
            "#SEPARATOR_COLON" to ": ",
            "#KEYWORDS" to "Keywords",
            "#KEYWORDS_PLACEHOLDER" to "Enter keywords",
            "#AUTHOR" to "Author",
            "#AUTHOR_PLACEHOLDER" to "Enter author",
            "#RATING_MINIMUM" to "Minimum rating",
            "#RATING_MAXIMUM" to "Maximum rating",
            "#SORT" to "Sort",
            "#DEFAULT" to "Default",
            "#POPULAR" to "Popular",
            "#NEWEST" to "Newest",
            "#ADVANCED" to "Advanced",
            "#FILTER" to "Filter",
            "#RANDOM" to "Random",
            "#LEVEL" to "Levels",
            "#REVIEWING" to "Reviewing",
            "#ANNOUNCEMENT" to "Announcements",
            "#SAME_AUTHOR" to "Same author",
            "#PINNED" to "Pinned",
            "#PUBLISHED" to "Published",
            "#STATUS" to "Status",
            "#LONG_VERSION" to "Long version",
            "#MULTI_FINGER" to "Multi finger",
            "#EXPLICIT" to "Explicit",
            "#EPILEPSY" to "Epilepsy",
        )
    }
}
