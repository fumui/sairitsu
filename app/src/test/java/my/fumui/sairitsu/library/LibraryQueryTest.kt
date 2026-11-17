package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.gameplay.Grade
import my.fumui.sairitsu.gameplay.PlayResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryQueryTest {
    private fun names(list: List<LibraryEntry>) = list.map { it.name }

    private fun result(score: Int) = PlayResult(Grade.Pass, score, 0, 0, 0, 0, 0, 0, 0)

    private fun key(name: String) = LevelKey("sonolus.sbuga.com", name)

    @Test
    fun collectionFilterIsOrAcrossCollections() {
        val entries = listOf(entry("a", addedAt = 3), entry("b", addedAt = 2), entry("c", addedAt = 1))
        val members = mapOf("x" to setOf(key("a")), "y" to setOf(key("b")))
        assertEquals(listOf("a", "b"), names(applyQuery(entries, emptyMap(), LibraryQuery(collections = setOf("x", "y")), members = members)))
        assertEquals(listOf("a"), names(applyQuery(entries, emptyMap(), LibraryQuery(collections = setOf("x")), members = members)))
    }

    @Test
    fun collectionFilterCombinesWithOtherFilters() {
        val entries = listOf(entry("a", engineName = "rush"), entry("b", engineName = "other"), entry("c", engineName = "rush"))
        val members = mapOf("x" to setOf(key("a"), key("b")))
        val query = LibraryQuery(collections = setOf("x"), engines = setOf("rush"))
        assertEquals(listOf("a"), names(applyQuery(entries, emptyMap(), query, members = members)))
    }

    @Test
    fun unknownCollectionIdsAreIgnored() {
        val entries = listOf(entry("a", addedAt = 2), entry("b", addedAt = 1))
        val members = mapOf("x" to setOf(key("a")))
        assertEquals(listOf("a"), names(applyQuery(entries, emptyMap(), LibraryQuery(collections = setOf("x", "missing")), members = members)))
        assertEquals(listOf("a", "b"), names(applyQuery(entries, emptyMap(), LibraryQuery(collections = setOf("missing")), members = members)))
    }

    @Test
    fun textMatchesTitleArtistsAuthorCaseInsensitive() {
        val entries = listOf(
            entry("a", artists = "Hatsune Miku & KASANE TETO"),
            entry("b", title = "Mikumiku"),
            entry("c", author = "MIKU fan"),
            entry("d", title = "other"),
        )
        assertEquals(setOf("a", "b", "c"), names(applyQuery(entries, emptyMap(), LibraryQuery(text = "miku"))).toSet())
    }

    @Test
    fun textMatchesEveryWordAcrossFieldsAndDescriptions() {
        val entries = listOf(
            entry("a", title = "プレデター", artists = "flower"),
            entry("b", title = "Monster", artists = "Noel"),
        )
        val descriptions = mapOf(key("a") to "プレデター ぷれでたー puredetaa pu redetaa")
        assertEquals(listOf("a"), names(applyQuery(entries, emptyMap(), LibraryQuery(text = "puredetaa"), descriptions)))
        assertEquals(listOf("a"), names(applyQuery(entries, emptyMap(), LibraryQuery(text = "Flower  PUREDETAA"), descriptions)))
        assertEquals(listOf("b"), names(applyQuery(entries, emptyMap(), LibraryQuery(text = "noel monster"), descriptions)))
        assertEquals(emptyList<String>(), names(applyQuery(entries, emptyMap(), LibraryQuery(text = "puredetaa"))))
    }

    @Test
    fun emptySetsMeanAll() {
        val entries = listOf(entry("a"), entry("b", server = "other.com"))
        assertEquals(2, applyQuery(entries, emptyMap(), LibraryQuery()).size)
    }

    @Test
    fun serverAndEngineMatchAny() {
        val entries = listOf(
            entry("a", server = "s1.com", engineName = "e1"),
            entry("b", server = "s2.com", engineName = "e2"),
            entry("c", server = "s3.com", engineName = "e1"),
        )
        val byServer = applyQuery(entries, emptyMap(), LibraryQuery(servers = setOf("s1.com", "s2.com")))
        assertEquals(setOf("a", "b"), names(byServer).toSet())
        val byEngine = applyQuery(entries, emptyMap(), LibraryQuery(engines = setOf("e1")))
        assertEquals(setOf("a", "c"), names(byEngine).toSet())
    }

    @Test
    fun tagsMatchAny() {
        val entries = listOf(
            entry("ex", tags = listOf("Expert", "VIRTUAL SINGER ver.")),
            entry("ma", tags = listOf("Master")),
            entry("ap", tags = listOf("Append")),
        )
        val out = applyQuery(entries, emptyMap(), LibraryQuery(tags = setOf("Expert", "Master")))
        assertEquals(setOf("ex", "ma"), names(out).toSet())
    }

    @Test
    fun tagsCompareRawTitles() {
        val entries = listOf(entry("a", tags = listOf("#EXPERT")), entry("b", tags = listOf("#EXPERT")), entry("c"))
        assertEquals(setOf("a", "b"), names(applyQuery(entries, emptyMap(), LibraryQuery(tags = setOf("#EXPERT")))).toSet())
    }

    @Test
    fun ratingRangeInclusive() {
        val entries = listOf(entry("a", rating = 24.0), entry("b", rating = 30.0), entry("c", rating = 36.0))
        val out = applyQuery(entries, emptyMap(), LibraryQuery(rating = RatingRange(24.0, 30.0)))
        assertEquals(setOf("a", "b"), names(out).toSet())
    }

    @Test
    fun staleRatingRangeIsClampedToFacets() {
        val entries = listOf(entry("a", rating = 24.0), entry("b", rating = 36.0))
        val overlap = applyQuery(entries, emptyMap(), LibraryQuery(rating = RatingRange(30.0, 50.0)))
        assertEquals(listOf("b"), names(overlap))
        // A range that misses the library entirely (left over after deletes) is ignored, not a dead end.
        val none = applyQuery(entries, emptyMap(), LibraryQuery(rating = RatingRange(40.0, 50.0)))
        assertEquals(setOf("a", "b"), names(none).toSet())
        val inverted = applyQuery(entries, emptyMap(), LibraryQuery(rating = RatingRange(50.0, 40.0)))
        assertEquals(setOf("a", "b"), names(inverted).toSet())
    }

    @Test
    fun rangeIgnoredWhenLibraryHasOneRating() {
        // The Difficulty chip is disabled then, so a saved range must not hide everything.
        val entries = listOf(entry("a", rating = 24.0), entry("b", rating = 24.0))
        val out = applyQuery(entries, emptyMap(), LibraryQuery(rating = RatingRange(20.0, 22.0)))
        assertEquals(setOf("a", "b"), names(out).toSet())
    }

    @Test
    fun facetsOfEmptyAndSingleRating() {
        assertNull(facets(emptyList()).ratingRange)
        assertEquals(RatingRange(24.0, 24.0), facets(listOf(entry("a", rating = 24.0))).ratingRange)
    }

    @Test
    fun facetsAreDistinctAndSorted() {
        val f = facets(
            listOf(
                entry("a", server = "b.com", engineName = "rush", tags = listOf("Master", "Expert")),
                entry("b", server = "a.com", engineName = "rush", tags = listOf("Expert"), rating = 30.0),
            ),
        )
        assertEquals(listOf("a.com", "b.com"), f.servers.map { it.first })
        assertEquals(listOf("rush"), f.engines.map { it.first })
        assertEquals(listOf("Expert", "Master"), f.tags)
        assertEquals(RatingRange(0.0, 30.0), f.ratingRange)
    }

    @Test
    fun playedFilterUsesBest() {
        val entries = listOf(entry("p"), entry("n"))
        val stats = mapOf(key("p") to LevelStats(best = result(1)), key("n") to LevelStats(lastPlayed = 5))
        assertEquals(listOf("p"), names(applyQuery(entries, stats, LibraryQuery(played = PlayedFilter.Played))))
        assertEquals(listOf("n"), names(applyQuery(entries, stats, LibraryQuery(played = PlayedFilter.NotPlayed))))
    }

    @Test
    fun sortAddedDescendingByDefault() {
        val entries = listOf(entry("old", addedAt = 1), entry("new", addedAt = 3), entry("mid", addedAt = 2))
        assertEquals(listOf("new", "mid", "old"), names(applyQuery(entries, emptyMap(), LibraryQuery())))
    }

    @Test
    fun sortTitleAscending() {
        val entries = listOf(entry("1", title = "banana"), entry("2", title = "Apple"), entry("3", title = "cherry"))
        val out = applyQuery(entries, emptyMap(), LibraryQuery(sort = LibrarySort.Title, descending = false))
        assertEquals(listOf("2", "1", "3"), names(out))
    }

    @Test
    fun sortBestScoreUsesArcadeScore() {
        val entries = listOf(entry("lo"), entry("hi"))
        val stats = mapOf(key("lo") to LevelStats(best = result(100)), key("hi") to LevelStats(best = result(900)))
        assertEquals(listOf("hi", "lo"), names(applyQuery(entries, stats, LibraryQuery(sort = LibrarySort.BestScore))))
    }

    @Test
    fun sortRating() {
        val entries = listOf(entry("a", rating = 30.0), entry("b", rating = 24.0))
        assertEquals(listOf("b", "a"), names(applyQuery(entries, emptyMap(), LibraryQuery(sort = LibrarySort.Rating, descending = false))))
    }

    @Test
    fun missingSortValuesGoLastInBothDirections() {
        val entries = listOf(entry("never"), entry("early"), entry("late"))
        val stats = mapOf(
            key("early") to LevelStats(best = result(1), lastPlayed = 10),
            key("late") to LevelStats(best = result(2), lastPlayed = 20),
        )
        for (sort in listOf(LibrarySort.LastPlayed, LibrarySort.BestScore)) {
            assertEquals(listOf("late", "early", "never"), names(applyQuery(entries, stats, LibraryQuery(sort = sort, descending = true))))
            assertEquals(listOf("early", "late", "never"), names(applyQuery(entries, stats, LibraryQuery(sort = sort, descending = false))))
        }
    }

    @Test
    fun queryRoundTripsJson() {
        val q = LibraryQuery(
            text = "x", servers = setOf("s"), engines = setOf("e"), tags = setOf("t"),
            rating = RatingRange(1.0, 2.0), played = PlayedFilter.Played, sort = LibrarySort.Rating, descending = false,
        )
        assertEquals(q, Json.decodeFromString(LibraryQuery.serializer(), Json.encodeToString(LibraryQuery.serializer(), q)))
    }
}
