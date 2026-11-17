package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SearchForms
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.OptionValue
import my.fumui.sairitsu.data.model.Search
import my.fumui.sairitsu.data.model.SearchOption
import my.fumui.sairitsu.data.model.Tag
import my.fumui.sairitsu.text.Localizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSearchTest {
    private val search = LocalSearch(Localizer("en"))

    private val noel = "##LOCALIZE:{\"en\":\"Shirogane Noel\",\"ja\":\"白銀ノエル\"}"
    private val items = listOf(
        LevelItem("m1-easy", "Monster", rating = 7.0, artists = noel, tags = listOf(Tag("#EASY"), Tag("VIRTUAL SINGER ver."))),
        LevelItem("m1-expert", "Monster", rating = 26.0, artists = noel, tags = listOf(Tag("#EXPERT"), Tag("VIRTUAL SINGER ver."))),
        LevelItem("p1-expert", "Peach", rating = 25.0, artists = "Poppin'Party", tags = listOf(Tag("#EXPERT"), Tag("SEKAI ver."))),
        LevelItem("p1-hard", "Peach", rating = 18.0, artists = "Poppin'Party", tags = listOf(Tag("#HARD"), Tag("SEKAI ver."))),
    )

    private fun multi(query: String, vararg values: Pair<String, String>) = SearchOption(
        query = query, name = "#$query", type = "multi",
        values = values.map { OptionValue(it.first, it.second) },
        def = JsonArray(values.map { JsonPrimitive(true) }),
    )

    private val form = Search(
        type = "advanced",
        title = "#ADVANCED",
        options = listOf(
            SearchOption("keywords", "#KEYWORDS", "text", def = JsonPrimitive("")),
            SearchOption("random", "#RANDOM", "toggle", def = JsonPrimitive(false)),
            multi("category", "vs" to "VIRTUAL SINGER ver.", "sekai" to "SEKAI ver."),
            SearchOption(
                "difficulty", "#DIFFICULTY", "select", def = JsonPrimitive("all"),
                values = listOf(OptionValue("all", "#ANY"), OptionValue("easy", "#EASY"), OptionValue("expert", "#EXPERT")),
            ),
            SearchOption("minrating", "#RATING_MINIMUM", "slider", def = JsonPrimitive(1), min = 1.0, max = 40.0, step = 1.0),
            SearchOption("maxrating", "#RATING_MAXIMUM", "slider", def = JsonPrimitive(40), min = 1.0, max = 40.0, step = 1.0),
            SearchOption(
                "sort", "#SORT", "select", def = JsonPrimitive("published_at"),
                values = listOf(OptionValue("published_at", "Published"), OptionValue("rating", "#RATING")),
            ),
            SearchOption("order", "#SORT", "select", def = JsonPrimitive("desc"), values = listOf(OptionValue("desc", "Desc"), OptionValue("asc", "Asc"))),
        ),
    )

    private fun values(vararg changes: Pair<Int, OptionState>) =
        SearchForms.defaults(form).toMutableList().also { list -> changes.forEach { list[it.first] = it.second } }

    private fun run(vararg changes: Pair<Int, OptionState>) = search.run(items, form, values(*changes))?.map { it.name }

    @Test
    fun defaultsKeepEverythingInSavedOrder() {
        assertEquals(items.map { it.name }, run())
    }

    @Test
    fun keywordsMatchEveryLanguage() {
        assertEquals(listOf("m1-easy", "m1-expert"), run(0 to OptionState.Text("白銀")))
        assertEquals(listOf("m1-expert"), run(0 to OptionState.Text("noel expert")))
        assertEquals(listOf("p1-expert", "p1-hard"), search.quick(items, "poppin").map { it.name })
    }

    @Test
    fun difficultyRatingAndCategory() {
        assertEquals(listOf("m1-expert", "p1-expert"), run(3 to OptionState.Select("expert")))
        assertEquals(listOf("p1-expert", "p1-hard"), run(4 to OptionState.Slider(18.0), 5 to OptionState.Slider(25.0)))
        assertEquals(listOf("p1-expert", "p1-hard"), run(2 to OptionState.Multi(listOf(false, true))))
    }

    @Test
    fun sortByRatingAscending() {
        assertEquals(
            listOf("m1-easy", "p1-hard", "p1-expert", "m1-expert"),
            run(6 to OptionState.Select("rating"), 7 to OptionState.Select("asc")),
        )
        assertEquals(items.map { it.name }.reversed(), run(7 to OptionState.Select("asc")))
    }

    @Test
    fun unrecognisedOptionsGoToTheServer() {
        val units = form.copy(options = form.options + multi("artists", "u1" to "Unit One", "u2" to "Unit Two", "pp" to "Poppin'Party"))
        val v = SearchForms.defaults(units).toMutableList().also { it[it.lastIndex] = OptionState.Multi(listOf(true, false, true)) }
        assertNull(search.run(items, units, v))
        val other = form.copy(options = form.options + SearchOption("genre", "#GENRE", "text", def = JsonPrimitive("")))
        val w = SearchForms.defaults(other).toMutableList().also { it[it.lastIndex] = OptionState.Text("rock") }
        assertNull(search.run(items, other, w))
    }
}
