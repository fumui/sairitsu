package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SearchForms
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.Search
import my.fumui.sairitsu.data.model.SearchOption
import my.fumui.sairitsu.text.Localizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * Runs a server's search form over its saved level list. Each option is mapped by what it means:
 * keywords (title, artists, author, tags in every language), rating sliders, difficulty, category
 * (tags), artists or characters (the artists text), sort, order and random. [run] returns null when an
 * option differs from its default and can't be answered from the list, so the caller asks the server.
 */
class LocalSearch(private val localizer: Localizer) {
    /** The searchable text of a level, worked out once per level. */
    private class Text(val keywords: String, val artists: String, val tags: Set<String>, val difficulty: Difficulty?, val title: String)

    // Searches run off the main thread, and a replaced one may still be finishing: share safely.
    private val texts = java.util.concurrent.ConcurrentHashMap<String, Text>()

    private fun text(level: LevelItem): Text = texts.getOrPut(level.name) {
        val tags = level.tags.flatMapTo(HashSet()) { variants(it.title) }
        val artists = variants(level.artists).joinToString("\n")
        val keywords = (variants(level.title) + variants(level.artists) + variants(level.author) + tags).joinToString("\n")
        Text(keywords, artists, tags, Difficulty.of(level, localizer), localizer.text(level.title).lowercase())
    }

    /** Levels whose title, artists, author or tags contain every word of [keywords]. */
    fun quick(items: List<LevelItem>, keywords: String): List<LevelItem> {
        val words = words(keywords)
        if (words.isEmpty()) return items
        return items.filter { level -> text(level).keywords.let { k -> words.all { it in k } } }
    }

    fun run(items: List<LevelItem>, form: Search, values: List<OptionState>, random: Random = Random.Default): List<LevelItem>? {
        val defaults = SearchForms.defaults(form)
        val sortIndices = SearchForms.sortOptions(form)
        val filters = mutableListOf<(LevelItem) -> Boolean>()
        var sort: Comparator<LevelItem>? = null
        var ascending = false
        var shuffle = false

        form.options.forEachIndexed { i, option ->
            val value = values.getOrNull(i) ?: return@forEachIndexed
            when (i) {
                sortIndices.sort -> {
                    val name = (value as? OptionState.Select)?.name?.lowercase() ?: return@forEachIndexed
                    sort = when {
                        "rating" in name || "level" in name || "difficulty" in name -> compareBy { it.rating }
                        "title" in name || "name" in name -> compareBy { text(it).title }
                        NEWEST_SORTS.any { it in name } -> null
                        else -> return null
                    }
                    return@forEachIndexed
                }
                sortIndices.order -> {
                    val name = (value as? OptionState.Select)?.name?.lowercase() ?: return@forEachIndexed
                    ascending = name.startsWith("asc")
                    return@forEachIndexed
                }
            }
            if (value == defaults[i]) return@forEachIndexed
            when (value) {
                is OptionState.Text -> {
                    val words = words(value.value)
                    when {
                        option.query == "keywords" -> filters += { level -> text(level).keywords.let { k -> words.all { it in k } } }
                        "author" in option.query.lowercase() ->
                            filters += { level -> variants(level.author).joinToString("\n").let { a -> words.all { it in a } } }
                        else -> return null
                    }
                }
                is OptionState.Toggle -> if (option.query.lowercase() == "random") shuffle = value.on else return null
                is OptionState.Slider -> {
                    if (!isRating(option)) return null
                    val bound = value.value
                    val minimum = isMinimum(option)
                    filters += { level: LevelItem -> if (minimum) level.rating >= bound else level.rating <= bound }
                }
                is OptionState.Select -> {
                    val chosen = option.values.firstOrNull { it.name == value.name } ?: return null
                    if (isAny(chosen.name, chosen.title)) return@forEachIndexed
                    val difficulty = Difficulty.of(listOf(chosen.title), localizer) ?: return null
                    filters += { level -> text(level).difficulty == difficulty }
                }
                is OptionState.Multi -> filters += multiFilter(items, option, value) ?: return null
                OptionState.Unsupported -> return null
            }
        }

        var result = items.filter { level -> filters.all { it(level) } }
        sort?.let { by -> result = result.sortedWith(if (ascending) by else by.reversed()) }
        if (sort == null && ascending) result = result.reversed()
        if (shuffle) result = result.shuffled(random)
        return result
    }

    /** A filter keeping levels that match any selected value; null when too few values can be recognised. */
    private fun multiFilter(items: List<LevelItem>, option: SearchOption, value: OptionState.Multi): ((LevelItem) -> Boolean)? {
        val matchers = option.values.map { v -> matcher(v.name, v.title) }
        // Values matching no saved level are either empty or something the list can't show (e.g. a unit
        // whose levels list its members): when that's common, the option can't be trusted locally.
        val recognised = matchers.count { m -> items.any(m) }
        if (recognised < matchers.size * RECOGNISED_SHARE) return null
        val selected = matchers.filterIndexed { j, _ -> value.selected.getOrElse(j) { false } }
        return { level -> selected.any { it(level) } }
    }

    private fun matcher(name: String, title: String): (LevelItem) -> Boolean {
        Difficulty.of(listOf(title), localizer)?.let { difficulty -> return { text(it).difficulty == difficulty } }
        val forms = (variants(title) + name.lowercase()).filter { it.length >= 2 }.toSet()
        return { level -> text(level).let { t -> forms.any { it in t.tags } || forms.any { it in t.artists } } }
    }

    /** Every language of a `##LOCALIZE:` text (or the text itself, resolved), lowercased. */
    private fun variants(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        val out = HashSet<String>()
        if (raw.startsWith(LOCALIZE)) {
            val map = runCatching { Json.parseToJsonElement(raw.removePrefix(LOCALIZE)) as? JsonObject }.getOrNull()
            map?.values?.forEach { (it as? JsonPrimitive)?.content?.let { s -> out += s.trim().lowercase() } }
        }
        out += localizer.text(raw).trim().lowercase()
        return out
    }

    private fun words(text: String) = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun isRating(option: SearchOption) =
        "rating" in option.query.lowercase() || option.name == "#RATING_MINIMUM" || option.name == "#RATING_MAXIMUM"

    private fun isMinimum(option: SearchOption) = option.query.lowercase().startsWith("min") || option.name == "#RATING_MINIMUM"

    private fun isAny(name: String, title: String) = name.lowercase() in ANY || title.uppercase() in setOf("#ANY", "#ALL")

    private companion object {
        const val LOCALIZE = "##LOCALIZE:"
        const val RECOGNISED_SHARE = 0.8
        val ANY = setOf("all", "any", "")
        val NEWEST_SORTS = listOf("publish", "newest", "date", "time", "created", "updated", "default", "id")
    }
}
