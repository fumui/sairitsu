package my.fumui.sairitsu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name.json")!!.readText()

    @Test
    fun parsesList() {
        val list = ServerJson.decodeFromString<ItemList>(fixture("list"))
        assertEquals(20, list.items.size)
        assertEquals("6853", list.cursor)
        assertEquals("coconut-horizon-6805", list.items[0].name)
        assertEquals(26.0, list.items[0].rating, 0.0)
        assertTrue(list.items[0].cover?.url!!.startsWith("https://"))
    }

    @Test
    fun parsesInfo() {
        val info = ServerJson.decodeFromString<ItemInfo>(fixture("info"))
        assertEquals(listOf("#ANNOUNCEMENT", "#RANDOM", "#LEVEL", "#REVIEWING"), info.sections.map { it.title })
        assertEquals(5, info.sections[1].levels(ServerJson).size)
        assertTrue(info.sections[0].levels(ServerJson).isEmpty())
        assertEquals(listOf("advanced", "filter"), info.searches.map { it.type })
    }

    @Test
    fun parsesDetails() {
        val details = ServerJson.decodeFromString<ItemDetails>(fixture("details"))
        assertEquals("coconut-horizon-6805", details.item.name)
        assertTrue(details.description!!.startsWith("This chart should have been finished"))
        assertEquals(listOf("#AUTHOR", "#SAME_AUTHOR"), details.sections.map { it.title })
        assertEquals(5, details.sections.first { it.title == "#SAME_AUTHOR" }.levels(ServerJson).size)
    }

    @Test
    fun malformedSectionItemsSkipped() {
        val section = ServerJson.decodeFromString<ItemSection>(
            """{"title":"#S","itemType":"level","items":[{"name":"bad"},{"name":"a","title":"A"},{"name":"a","title":"A"}]}""",
        )
        assertEquals(listOf("a"), section.levels(ServerJson).map { it.name })
    }

    @Test
    fun iconOnlyTagParses() {
        // untitledcharts.com sends staff-pick badges as {"icon":"trophy"} with no title.
        val level = ServerJson.decodeFromString<LevelItem>(
            """{"name":"x","title":"T","tags":[{"icon":"trophy"},{"title":"Expert"}]}""",
        )
        assertEquals(listOf(Tag("", "trophy"), Tag("Expert")), level.tags)
    }

    @Test
    fun parsesMinimalLevelAndOddOptions() {
        val level = ServerJson.decodeFromString<LevelItem>("""{"name":"x","title":"T","rating":12.5}""")
        assertEquals("", level.artists)
        assertNull(level.cover)
        assertEquals(12.5, level.rating, 0.0)

        val option = ServerJson.decodeFromString<SearchOption>(
            """{"query":"q","name":"#N","type":"color","def":"#fff"}""",
        )
        assertEquals("color", option.type)
    }
}
