package my.fumui.sairitsu.data

import my.fumui.sairitsu.data.model.Search
import my.fumui.sairitsu.data.model.SearchOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** The current value of one search option. */
sealed interface OptionState {
    data class Text(val value: String) : OptionState
    data class Toggle(val on: Boolean) : OptionState
    data class Select(val name: String) : OptionState
    data class Multi(val selected: List<Boolean>) : OptionState
    data class Slider(val value: Double) : OptionState
    data object Unsupported : OptionState
}

/** How options are laid out: alone, or a min/max slider pair shown as one range. */
sealed interface OptionGroup {
    data class Single(val index: Int) : OptionGroup
    data class Range(val minIndex: Int, val maxIndex: Int) : OptionGroup
}

object SearchForms {
    fun defaults(search: Search): List<OptionState> = search.options.map(::defaultOf)

    fun query(search: Search, values: List<OptionState>): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        params["type"] = search.type
        search.options.forEachIndexed { i, option ->
            val value = values.getOrNull(i) ?: return@forEachIndexed
            if (value == OptionState.Unsupported || value == defaultOf(option)) return@forEachIndexed
            params[option.query] = when (value) {
                is OptionState.Text -> value.value
                is OptionState.Toggle -> if (value.on) "1" else "0"
                is OptionState.Select -> value.name
                is OptionState.Multi -> option.values
                    .filterIndexed { j, _ -> value.selected.getOrElse(j) { false } }
                    .joinToString(",") { it.name }
                is OptionState.Slider -> formatNumber(value.value)
                OptionState.Unsupported -> return@forEachIndexed
            }
        }
        return params
    }

    fun quick(keywords: String): Map<String, String> = linkedMapOf("type" to "quick", "keywords" to keywords)

    fun groups(search: Search): List<OptionGroup> {
        val options = search.options
        val paired = mutableSetOf<Int>()
        val groups = mutableListOf<OptionGroup>()
        options.forEachIndexed { i, option ->
            if (i in paired) return@forEachIndexed
            val partner = partnerOf(options, i)
            if (partner != null && partner !in paired) {
                paired += setOf(i, partner)
                val minIndex = if (option.query.startsWith("min")) i else partner
                val maxIndex = if (minIndex == i) partner else i
                groups += OptionGroup.Range(minIndex, maxIndex)
            } else {
                groups += OptionGroup.Single(i)
            }
        }
        return groups
    }

    /** Whether [text] fits the option's length limit; a missing or non-positive limit means unlimited. */
    fun acceptsText(option: SearchOption, text: String): Boolean {
        val limit = option.limit ?: return true
        return limit <= 0 || text.length <= limit
    }

    /** The slider's step, falling back to 1 when the server sends none or a non-positive one. */
    fun sliderStep(option: SearchOption): Double = option.step?.takeIf { it > 0 } ?: 1.0

    /**
     * The title of a min/max pair, from its (localized) names: "Minimum Rating" and "Maximum Rating" give "Rating".
     * When the names don't share a subject, the query's: `min_play_count` or `minPlayCount` gives "Play count".
     */
    fun rangeLabel(minName: String, maxName: String, minQuery: String): String {
        val lower = subject(minName, MIN_WORDS)
        val upper = subject(maxName, MAX_WORDS)
        if (lower != null && lower.equals(upper, ignoreCase = true)) return lower.replaceFirstChar { it.titlecase() }
        return minQuery.drop(3)
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("[_\\-]+"), " ")
            .trim()
            .lowercase()
            .replaceFirstChar { it.titlecase() }
    }

    /** Whether a range has more than [MAX_SLIDER_VALUES] steps, too many to pick on a slider: two number fields instead. */
    fun usesNumberFields(option: SearchOption): Boolean {
        val min = option.min ?: return false
        val max = option.max ?: return false
        return Math.floor((max - min) / sliderStep(option) + 1e-9) + 1 > MAX_SLIDER_VALUES
    }

    /** A typed range bound: clamped into the option's bounds and snapped to its step; null when not a number. */
    fun parseRangeNumber(text: String, option: SearchOption): Double? {
        val value = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val min = option.min ?: return value
        val max = option.max ?: return value
        val step = sliderStep(option)
        val snapped = min + Math.round((value.coerceIn(min, max) - min) / step) * step
        return snapped.coerceIn(min, max)
    }

    /** [name] without its leading or trailing bound word ("Minimum rating", "Rating max"), or null without one. */
    private fun subject(name: String, words: List<String>): String? {
        val trimmed = name.trim()
        for (word in words) {
            if (trimmed.startsWith("$word ", ignoreCase = true)) return trimmed.drop(word.length + 1).trim().ifEmpty { null }
            if (trimmed.endsWith(" $word", ignoreCase = true)) return trimmed.dropLast(word.length + 1).trim().ifEmpty { null }
        }
        return null
    }

    private const val MAX_SLIDER_VALUES = 50
    private val MIN_WORDS = listOf("minimum", "min.", "min")
    private val MAX_WORDS = listOf("maximum", "max.", "max")

    fun keywordsIndex(search: Search): Int? =
        search.options.indexOfFirst { it.query == "keywords" && it.type in TEXT_TYPES }.takeIf { it >= 0 }

    /** The form search opens with: the richest one that has keywords, else the first; -1 when there are none. */
    fun defaultForm(forms: List<Search>): Int {
        if (forms.isEmpty()) return -1
        val withKeywords = forms.indices.filter { keywordsIndex(forms[it]) != null }
        return withKeywords.maxByOrNull { forms[it].options.size } ?: 0
    }

    /** The form's sort field and sort direction (both `select` options), either of which can be missing. */
    fun sortOptions(search: Search): SortOptions {
        var sort: Int? = null
        var order: Int? = null
        search.options.forEachIndexed { i, option ->
            if (option.type != "select") return@forEachIndexed
            val query = option.query.lowercase()
            when {
                query in ORDER_QUERIES -> if (order == null) order = i
                query in SORT_QUERIES || option.name == "#SORT" -> if (sort == null) sort = i
            }
        }
        return SortOptions(sort, order)
    }

    /** The groups shown as filters: everything except the keywords and the sort options. */
    fun filterGroups(search: Search): List<OptionGroup> {
        val shownElsewhere = setOfNotNull(keywordsIndex(search)) + sortOptions(search).indices
        return groups(search).filter { it !is OptionGroup.Single || it.index !in shownElsewhere }
    }

    /** How many filter groups differ from their defaults. */
    fun activeFilters(search: Search, values: List<OptionState>): Int = filterGroups(search).count { group ->
        val indices = when (group) {
            is OptionGroup.Single -> listOf(group.index)
            is OptionGroup.Range -> listOf(group.minIndex, group.maxIndex)
        }
        indices.any { i -> values.getOrNull(i)?.let { it != defaultOf(search.options[i]) } == true }
    }

    /** [values] with every filter reset to its default; keywords and sort are kept. */
    fun clearFilters(search: Search, values: List<OptionState>): List<OptionState> {
        val keep = setOfNotNull(keywordsIndex(search)) + sortOptions(search).indices
        return search.options.mapIndexed { i, option -> if (i in keep) values[i] else defaultOf(option) }
    }

    private fun isValidSlider(option: SearchOption): Boolean {
        val min = option.min ?: return false
        val max = option.max ?: return false
        return min < max
    }

    private fun partnerOf(options: List<SearchOption>, i: Int): Int? {
        val option = options[i]
        if (option.type != "slider" || !isValidSlider(option)) return null
        val (prefix, suffix) = splitMinMax(option.query) ?: return null
        val otherPrefix = if (prefix == "min") "max" else "min"
        return options.indices.firstOrNull { j ->
            val other = options[j]
            j != i && other.type == "slider" && splitMinMax(other.query) == (otherPrefix to suffix) &&
                other.min == option.min && other.max == option.max && other.step == option.step
        }
    }

    private fun splitMinMax(query: String): Pair<String, String>? {
        val prefix = query.take(3)
        val suffix = query.drop(3)
        if (prefix != "min" && prefix != "max") return null
        // Servers spell these `minRating` or `minrating`; the matching bounds and step keep false pairs out.
        if (suffix.isEmpty()) return null
        return prefix to suffix.lowercase()
    }

    fun defaultOf(option: SearchOption): OptionState {
        val def = option.def
        val primitive = def as? JsonPrimitive
        return when (option.type) {
            in TEXT_TYPES -> OptionState.Text(primitive?.takeIf { it.isString }?.content ?: "")
            "toggle" -> OptionState.Toggle(primitive?.booleanOrNull ?: false)
            "select" -> OptionState.Select(
                primitive?.takeIf { it.isString }?.content ?: option.values.firstOrNull()?.name ?: "",
            )
            "multi" -> OptionState.Multi(multiDefault(option))
            "slider" -> if (isValidSlider(option)) OptionState.Slider(primitive?.doubleOrNull ?: option.min!!) else OptionState.Unsupported
            else -> OptionState.Unsupported
        }
    }

    private fun multiDefault(option: SearchOption): List<Boolean> {
        val entries = (option.def as? JsonArray).orEmpty().mapNotNull { it as? JsonPrimitive }
        val names = entries.filter { it.isString }.map { it.content }.toSet()
        return option.values.mapIndexed { j, value ->
            val flag = entries.getOrNull(j)?.takeIf { !it.isString }?.booleanOrNull
            flag ?: (value.name in names)
        }
    }

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private val TEXT_TYPES = setOf("text", "textArea")
    private val SORT_QUERIES = setOf("sort", "sortby", "orderby")
    private val ORDER_QUERIES = setOf("order", "direction", "sortorder", "sortdirection")
}

/** Option indices of a form's sort field and sort direction. */
data class SortOptions(val sort: Int?, val order: Int?) {
    val indices: Set<Int> get() = setOfNotNull(sort, order)
    val isEmpty: Boolean get() = sort == null && order == null
}
