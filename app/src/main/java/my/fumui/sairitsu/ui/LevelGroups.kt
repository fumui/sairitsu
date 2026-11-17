package my.fumui.sairitsu.ui

import androidx.compose.ui.graphics.Color
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.text.Localizer

/** The usual chart difficulties, each with the color its badges use. */
enum class Difficulty(val color: Color) {
    Easy(Color(0xFF6FDC8C)),
    Normal(Color(0xFF6CB8FF)),
    Hard(Color(0xFFFFC857)),
    Expert(Color(0xFFFF6B6B)),
    Master(Color(0xFFC88BFF)),
    Append(Color(0xFFFF8AD8)),
    Special(Color(0xFFFF8AD8)),
    ;

    companion object {
        private val byName = entries.associateBy { it.name.lowercase() }

        /** The difficulty one of [tags] (raw titles, `#EXPERT` or `Expert`) names, or null. */
        fun of(tags: List<String>, localizer: Localizer): Difficulty? =
            tags.firstNotNullOfOrNull { byName[localizer.text(it).trim().lowercase()] }

        fun of(level: LevelItem, localizer: Localizer): Difficulty? = of(level.tags.map { it.title }, localizer)
    }
}

/** Neighbouring list rows that are the same song: [indices] into the flat list, ordered by rating. */
data class LevelGroup(val indices: List<Int>, val levels: List<LevelItem>) {
    /** The row's title, artists and cover come from the first level the server listed. */
    val first: LevelItem get() = levels.first()
    val firstIndex: Int get() = indices.first()

    /** The group's charts from easiest to hardest. */
    val byRating: List<Pair<Int, LevelItem>> get() = indices.zip(levels).sortedBy { it.second.rating }
}

/**
 * Folds runs of consecutive levels with the same title, artists, author and engine into one group each
 * (servers list a song's difficulties next to each other). Order is kept; non-adjacent repeats stay apart.
 */
fun groupLevels(items: List<LevelItem>): List<LevelGroup> {
    val groups = mutableListOf<LevelGroup>()
    var start = 0
    while (start < items.size) {
        val key = items[start].songKey()
        var end = start + 1
        while (end < items.size && items[end].songKey() == key) end++
        groups += LevelGroup((start until end).toList(), items.subList(start, end).toList())
        start = end
    }
    return groups
}

/**
 * Moves every chart of a song next to the song's first chart in [items], keeping the songs in the
 * order they first appear (a new difficulty added at the top brings its song up with it).
 */
fun clusterSongs(items: List<LevelItem>): List<LevelItem> {
    val songs = LinkedHashMap<List<Any?>, MutableList<LevelItem>>()
    items.forEach { songs.getOrPut(it.songKey()) { ArrayList() } += it }
    return if (songs.size == items.size) items else songs.values.flatten()
}

private fun LevelItem.songKey() = listOf(title, artists, author, engine?.name)
