package my.fumui.sairitsu.data

import my.fumui.sairitsu.data.model.ItemInfo
import my.fumui.sairitsu.data.model.OptionValue
import my.fumui.sairitsu.data.model.Search
import my.fumui.sairitsu.data.model.SearchOption
import my.fumui.sairitsu.data.model.ServerJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFormsTest {
    private val info: ItemInfo =
        ServerJson.decodeFromString(javaClass.getResource("/fixtures/info.json")!!.readText())
    private val adv: Search = info.searches.first { it.type == "advanced" }

    private fun index(query: String) = adv.options.indexOfFirst { it.query == query }

    @Test
    fun defaultsQueryIsTypeOnly() {
        assertEquals(mapOf("type" to "advanced"), SearchForms.query(adv, SearchForms.defaults(adv)))
    }

    @Test
    fun changedValuesAppear() {
        val values = SearchForms.defaults(adv).toMutableList()
        values[index("keywords")] = OptionState.Text("love")
        values[index("sort")] = OptionState.Select("popular")
        values[index("includeTagIds")] = OptionState.Multi(listOf(true, false, true, false))
        values[index("minRating")] = OptionState.Slider(20.0)

        val expected = listOf(
            "type" to "advanced",
            "keywords" to "love",
            "includeTagIds" to "1,4",
            "minRating" to "20",
            "sort" to "popular",
        )
        assertEquals(expected, SearchForms.query(adv, values).toList())
    }

    @Test
    fun multiDefaultsFromNames() {
        val search = Search(
            type = "t",
            title = "T",
            options = listOf(
                SearchOption(
                    query = "m",
                    name = "#M",
                    type = "multi",
                    def = JsonArray(listOf(JsonPrimitive("b"))),
                    values = listOf(OptionValue("a", "A"), OptionValue("b", "B")),
                ),
            ),
        )
        assertEquals(listOf(OptionState.Multi(listOf(false, true))), SearchForms.defaults(search))
    }

    @Test
    fun unsupportedOmitted() {
        val search = Search(
            type = "t",
            title = "T",
            options = listOf(SearchOption(query = "item", name = "#ITEM", type = "serverItem")),
        )
        val values = SearchForms.defaults(search)
        assertEquals(listOf(OptionState.Unsupported), values)
        assertEquals(mapOf("type" to "t"), SearchForms.query(search, values))
    }

    @Test
    fun ratingSlidersGroupIntoRange() {
        val ranges = SearchForms.groups(adv).filterIsInstance<OptionGroup.Range>()
        assertEquals(listOf(OptionGroup.Range(index("minRating"), index("maxRating"))), ranges)
        assertEquals(adv.options.size - 1, SearchForms.groups(adv).size)
    }

    @Test
    fun limitZeroMeansUnlimited() {
        fun text(limit: Int?) = SearchOption(query = "q", name = "#Q", type = "text", limit = limit)
        assertEquals(true, SearchForms.acceptsText(text(0), "abc"))
        assertEquals(true, SearchForms.acceptsText(text(null), "abc"))
        assertEquals(false, SearchForms.acceptsText(text(2), "abc"))
        assertEquals(true, SearchForms.acceptsText(text(3), "abc"))
    }

    @Test
    fun invalidSlidersAreUnsupportedAndStepIsPositive() {
        fun slider(min: Double?, max: Double?, step: Double?) =
            SearchOption(query = "s", name = "#S", type = "slider", min = min, max = max, step = step)
        val bad = Search(type = "t", title = "T", options = listOf(slider(5.0, 5.0, 1.0), slider(9.0, 1.0, 1.0), slider(null, null, null)))
        assertEquals(List(3) { OptionState.Unsupported }, SearchForms.defaults(bad))
        assertEquals(1.0, SearchForms.sliderStep(slider(0.0, 10.0, 0.0)), 0.0)
        assertEquals(1.0, SearchForms.sliderStep(slider(0.0, 10.0, null)), 0.0)
        assertEquals(0.5, SearchForms.sliderStep(slider(0.0, 10.0, 0.5)), 0.0)
    }

    @Test
    fun quickSearch() {
        assertEquals(mapOf("type" to "quick", "keywords" to "love"), SearchForms.quick("love"))
    }

    @Test
    fun keywordsIndexFindsTextOption() {
        assertEquals(index("keywords"), SearchForms.keywordsIndex(adv))
        assertEquals(null, SearchForms.keywordsIndex(info.searches.first { it.type == "filter" }))
    }

    private fun select(query: String, name: String = "#${query.uppercase()}") =
        SearchOption(query = query, name = name, type = "select", values = listOf(OptionValue("a", "A"), OptionValue("b", "B")))

    private fun slider(query: String) = SearchOption(query = query, name = "#R", type = "slider", min = 5.0, max = 38.0)

    private val keywords = SearchOption(query = "keywords", name = "#KEYWORDS", type = "text")

    // Shaped like a real server's advanced form: lowercase min/max queries and sort + order selects both named #SORT.
    private val lowercaseForm = Search(
        type = "advanced",
        title = "#ADVANCED",
        options = listOf(
            keywords,
            select("difficulty"),
            slider("minrating"),
            slider("maxrating"),
            select("sort", "#SORT"),
            select("order", "#SORT"),
        ),
    )

    @Test
    fun lowercaseMinMaxSlidersPair() {
        assertEquals(listOf(OptionGroup.Range(2, 3)), SearchForms.groups(lowercaseForm).filterIsInstance<OptionGroup.Range>())
    }

    @Test
    fun sortAndOrderFound() {
        assertEquals(SortOptions(sort = 4, order = 5), SearchForms.sortOptions(lowercaseForm))
        assertEquals(SortOptions(sort = index("sort"), order = null), SearchForms.sortOptions(adv))
        assertEquals(true, SearchForms.sortOptions(info.searches.first { it.type == "filter" }).isEmpty)
    }

    @Test
    fun filterGroupsLeaveOutKeywordsAndSort() {
        assertEquals(
            listOf(OptionGroup.Single(1), OptionGroup.Range(2, 3)),
            SearchForms.filterGroups(lowercaseForm),
        )
    }

    @Test
    fun activeFiltersCountsGroupsAndClearKeepsKeywordsAndSort() {
        val values = SearchForms.defaults(lowercaseForm).toMutableList()
        values[0] = OptionState.Text("miku")
        values[4] = OptionState.Select("b")
        assertEquals(0, SearchForms.activeFilters(lowercaseForm, values))
        values[1] = OptionState.Select("b")
        values[2] = OptionState.Slider(10.0)
        values[3] = OptionState.Slider(20.0)
        assertEquals(2, SearchForms.activeFilters(lowercaseForm, values))

        val cleared = SearchForms.clearFilters(lowercaseForm, values)
        assertEquals(0, SearchForms.activeFilters(lowercaseForm, cleared))
        assertEquals(OptionState.Text("miku"), cleared[0])
        assertEquals(OptionState.Select("b"), cleared[4])
    }

    @Test
    fun defaultFormIsRichestWithKeywords() {
        val quick = Search(type = "quick", title = "#KEYWORDS", options = listOf(keywords))
        val noKeywords = Search(type = "f", title = "F", options = List(9) { select("o$it") })
        assertEquals(2, SearchForms.defaultForm(listOf(noKeywords, quick, lowercaseForm)))
        assertEquals(0, SearchForms.defaultForm(listOf(noKeywords)))
        assertEquals(-1, SearchForms.defaultForm(emptyList()))
    }

    @Test
    fun rangeLabelComesFromTheOptionNames() {
        // UntitledCharts: min_rating "Minimum Rating" / max_rating "Maximum Rating".
        assertEquals("Rating", SearchForms.rangeLabel("Minimum Rating", "Maximum Rating", "min_rating"))
        // Localized keys resolve to "Minimum rating" / "Maximum rating".
        assertEquals("Rating", SearchForms.rangeLabel("Minimum rating", "Maximum rating", "minRating"))
        assertEquals("Likes", SearchForms.rangeLabel("Min likes", "Max likes", "min_likes"))
        assertEquals("Rating", SearchForms.rangeLabel("Rating minimum", "Rating maximum", "minRating"))
    }

    @Test
    fun rangeLabelFallsBackToTheQuery() {
        // Names that don't share a subject: the query's subject, without separators.
        assertEquals("Rating", SearchForms.rangeLabel("From", "To", "min_rating"))
        assertEquals("Play count", SearchForms.rangeLabel("A", "B", "minPlayCount"))
        assertEquals("Like count", SearchForms.rangeLabel("A", "B", "min-like_count"))
    }

    private fun bounded(min: Double, max: Double, step: Double? = 1.0) =
        SearchOption(query = "min_x", name = "Minimum X", type = "slider", min = min, max = max, step = step)

    @Test
    fun wideRangesUseNumberFields() {
        assertEquals(false, SearchForms.usesNumberFields(bounded(5.0, 38.0)))       // 34 values
        assertEquals(false, SearchForms.usesNumberFields(bounded(1.0, 50.0)))       // 50 values
        assertEquals(true, SearchForms.usesNumberFields(bounded(0.0, 50.0)))        // 51 values
        assertEquals(true, SearchForms.usesNumberFields(bounded(-999.0, 999.0)))
        assertEquals(false, SearchForms.usesNumberFields(bounded(0.0, 10.0, 0.5)))  // 21 values
        assertEquals(true, SearchForms.usesNumberFields(bounded(0.0, 100.0, 0.5)))  // 201 values
    }

    @Test
    fun rangeNumbersAreClampedAndSnapped() {
        val o = bounded(-999.0, 999.0)
        assertEquals(42.0, SearchForms.parseRangeNumber("42", o)!!, 0.0)
        assertEquals(-5.0, SearchForms.parseRangeNumber(" -5 ", o)!!, 0.0)
        assertEquals(999.0, SearchForms.parseRangeNumber("5000", o)!!, 0.0)
        assertEquals(-999.0, SearchForms.parseRangeNumber("-5000", o)!!, 0.0)
        assertEquals(3.0, SearchForms.parseRangeNumber("2.6", o)!!, 0.0)
        assertEquals(null, SearchForms.parseRangeNumber("", o))
        assertEquals(null, SearchForms.parseRangeNumber("abc", o))
        assertEquals(1.5, SearchForms.parseRangeNumber("1.4", bounded(0.0, 100.0, 0.5))!!, 0.0)
    }
}
