package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.library.LibraryEntry
import my.fumui.sairitsu.library.LevelStats
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewLevelsTest {
    private fun entry(server: String, name: String, addedAt: Long) = LibraryEntry(
        server = server, serverTitle = server, name = name, title = name, artists = "", author = "", rating = 0.0,
        tags = emptyList(), engineName = null, engineTitle = null, addedAt = addedAt, resourceKeys = emptyMap(),
        detailsKey = "", coverKey = null, previewKey = null,
    )

    private fun names(levels: List<PreviewLevel>) = levels.map { it.entry.name }

    @Test
    fun otherServersExcluded() {
        val entries = listOf(entry("a.com", "x", 1), entry("b.com", "y", 2))
        assertEquals(listOf("x"), names(previewLevels("a.com", entries, emptyMap())))
    }

    @Test
    fun playedFirstNewestFirst() {
        val entries = listOf(entry("s", "old", 1), entry("s", "new", 2), entry("s", "unplayed", 3))
        val stats = mapOf(
            LevelKey("s", "old") to LevelStats(lastPlayed = 100),
            LevelKey("s", "new") to LevelStats(lastPlayed = 200),
        )
        val levels = previewLevels("s", entries, stats)
        assertEquals(listOf("new", "old", "unplayed"), names(levels))
        assertEquals(listOf<Long?>(200, 100, null), levels.map { it.lastPlayed })
    }

    @Test
    fun unplayedByAddedAt() {
        val entries = listOf(entry("s", "first", 1), entry("s", "third", 3), entry("s", "second", 2))
        assertEquals(listOf("third", "second", "first"), names(previewLevels("s", entries, emptyMap())))
    }
}
