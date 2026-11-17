package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.Tag
import my.fumui.sairitsu.text.Localizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelGroupsTest {
    private fun level(name: String, title: String, rating: Double, artists: String = "Miku") =
        LevelItem(name = name, title = title, rating = rating, artists = artists)

    @Test
    fun groupsAdjacentSameSong() {
        val items = listOf(
            level("a1", "A", 26.0), level("a2", "A", 18.0), level("a3", "A", 7.0),
            level("a4", "A", 7.0, artists = "Rin"),
            level("b1", "B", 5.0),
            level("a5", "A", 30.0),
        )
        val groups = groupLevels(items)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3), listOf(4), listOf(5)), groups.map { it.indices })
        assertEquals(listOf("a3", "a2", "a1"), groups[0].byRating.map { it.second.name })
        assertEquals("a1", groups[0].first.name)
    }

    @Test
    fun clusterBringsChartsOfASongTogether() {
        val items = listOf(
            level("a-append", "A", 30.0), level("b1", "B", 5.0), level("a1", "A", 7.0), level("b2", "B", 9.0), level("a2", "A", 20.0),
        )
        assertEquals(listOf("a-append", "a1", "a2", "b1", "b2"), clusterSongs(items).map { it.name })
    }

    @Test
    fun emptyList() {
        assertEquals(emptyList<LevelGroup>(), groupLevels(emptyList<LevelItem>()))
    }

    @Test
    fun difficultyFromTags() {
        val en = Localizer("en")
        assertEquals(Difficulty.Expert, Difficulty.of(listOf("VIRTUAL SINGER ver.", "Expert"), en))
        assertEquals(Difficulty.Master, Difficulty.of(LevelItem("x", "x", tags = listOf(Tag("#MASTER"))), en))
        assertNull(Difficulty.of(listOf("Original"), en))
    }
}
