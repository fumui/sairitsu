package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.gameplay.PlayResult
import kotlinx.serialization.Serializable

/** What the device knows about playing a level: the best Play result and when it was last played or watched. */
data class LevelStats(val best: PlayResult? = null, val lastPlayed: Long? = null)

enum class LibrarySort { Added, Title, LastPlayed, BestScore, Rating }

enum class PlayedFilter { All, Played, NotPlayed }

/** An inclusive rating range. */
@Serializable
data class RatingRange(val min: Double, val max: Double)

/**
 * The Play list's search, filters and sort. Empty sets mean "any"; a level matches a set when it has any of its values.
 * [tags] holds raw tag titles.
 */
@Serializable
data class LibraryQuery(
    val text: String = "",
    val servers: Set<String> = emptySet(),
    val engines: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val rating: RatingRange? = null,
    val played: PlayedFilter = PlayedFilter.All,
    val sort: LibrarySort = LibrarySort.Added,
    val descending: Boolean = true,
    /** Collection ids; a level matches when it's in any of them. Ids that no longer exist are ignored. */
    val collections: Set<String> = emptySet(),
)

/** The values present in the library, for the filter controls. */
data class LibraryFacets(
    /** linkAddress to server title. */
    val servers: List<Pair<String, String>>,
    /** Engine name to engine title. */
    val engines: List<Pair<String, String>>,
    val tags: List<String>,
    /** Null when the library is empty. */
    val ratingRange: RatingRange?,
)

fun facets(entries: List<LibraryEntry>): LibraryFacets = LibraryFacets(
    servers = entries.associate { it.server to it.serverTitle }.toList().sortedBy { it.first },
    engines = entries.mapNotNull { e -> e.engineName?.let { it to (e.engineTitle ?: it) } }.toMap().toList().sortedBy { it.first },
    tags = entries.flatMap { it.tags }.distinct().sorted(),
    ratingRange = if (entries.isEmpty()) null else RatingRange(entries.minOf { it.rating }, entries.maxOf { it.rating }),
)

/**
 * The entries matching [query], sorted by it. Ties keep the newest download first; levels with no value for the sort
 * key (never played) go last in either direction.
 *
 * The search text matches when every word of it is in the title, artists, author, tags or the level's
 * [searchText] (its description, which holds readings such as romaji: "puredetaa" for プレデター).
 */
fun applyQuery(
    entries: List<LibraryEntry>,
    stats: Map<LevelKey, LevelStats>,
    query: LibraryQuery,
    searchText: Map<LevelKey, String> = emptyMap(),
    /** Each collection's members, by collection id. */
    members: Map<String, Set<LevelKey>> = emptyMap(),
): List<LibraryEntry> {
    val words = query.text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val inCollections = query.collections.mapNotNull(members::get)
    val rating = effectiveRating(query.rating, facets(entries).ratingRange)
    val matching = entries.filter { e ->
        (words.isEmpty() || haystack(e, searchText[e.key]).let { h -> words.all { it in h } }) &&
            (inCollections.isEmpty() || inCollections.any { e.key in it }) &&
            (query.servers.isEmpty() || e.server in query.servers) &&
            (query.engines.isEmpty() || e.engineName in query.engines) &&
            (query.tags.isEmpty() || e.tags.any { it in query.tags }) &&
            (rating == null || e.rating >= rating.min && e.rating <= rating.max) &&
            when (query.played) {
                PlayedFilter.All -> true
                PlayedFilter.Played -> stats[e.key]?.best != null
                PlayedFilter.NotPlayed -> stats[e.key]?.best == null
            }
    }
    val newestFirst = compareByDescending<LibraryEntry> { it.addedAt }
    val byKey: Comparator<LibraryEntry> = when (query.sort) {
        LibrarySort.Added -> compareBy { it.addedAt }
        LibrarySort.Title -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        LibrarySort.Rating -> compareBy { it.rating }
        LibrarySort.LastPlayed -> nullsLast(query.descending) { stats[it.key]?.lastPlayed }
        LibrarySort.BestScore -> nullsLast(query.descending) { stats[it.key]?.best?.arcadeScore?.toLong() }
    }
    val directed = if (query.descending && query.sort !in NULLABLE_SORTS) byKey.reversed() else byKey
    return matching.sortedWith(directed.then(newestFirst))
}

private val NULLABLE_SORTS = setOf(LibrarySort.LastPlayed, LibrarySort.BestScore)

/** Everything a search looks in, lowercased; raw `##LOCALIZE:` texts keep every language. */
private fun haystack(e: LibraryEntry, extra: String?): String =
    (listOf(e.title, e.artists, e.author) + e.tags + listOfNotNull(extra)).joinToString("\n").lowercase()

/**
 * The saved range, or null (no filter) when it can't be changed or would hide everything for a stale reason: the
 * library has one rating (the Rating slider is hidden then) or the range misses the library's ratings entirely
 * (left over after deletes). A range that overlaps them is used as is.
 */
fun effectiveRating(range: RatingRange?, bounds: RatingRange?): RatingRange? {
    if (range == null || bounds == null || bounds.min >= bounds.max) return null
    if (range.min > range.max || range.max < bounds.min || range.min > bounds.max) return null
    return range
}

/** Orders by [value] in the given direction, with nulls last either way. */
private fun nullsLast(descending: Boolean, value: (LibraryEntry) -> Long?): Comparator<LibraryEntry> {
    val order: Comparator<Long> = if (descending) reverseOrder() else naturalOrder()
    return compareBy(nullsLast(order), value)
}
