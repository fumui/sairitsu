package my.fumui.sairitsu.data

import my.fumui.sairitsu.data.model.OptionValue
import my.fumui.sairitsu.data.model.SearchOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerConfigTest {
    @get:Rule val tmp = TemporaryFolder()

    private val genre = SearchOption(
        "genre", "Genre", "select", def = JsonPrimitive("pop"),
        values = listOf(OptionValue("pop", "Pop"), OptionValue("rock", "Rock")),
    )
    private val nsfw = SearchOption("nsfw", "NSFW", "toggle", def = JsonPrimitive(false))
    private val rating = SearchOption("minRating", "Rating", "slider", def = JsonPrimitive(0), min = 0.0, max = 100.0, step = 1.0)
    private val file = SearchOption("f", "File", "file", def = JsonPrimitive(""))
    private val options = listOf(genre, nsfw, rating, file)
    private val values = listOf(
        OptionState.Select("rock"), OptionState.Toggle(false), OptionState.Slider(75.0), OptionState.Unsupported,
    )

    @Test
    fun encodeSkipsDefaultsAndUnsupported() {
        assertEquals(mapOf("genre" to "rock", "minRating" to "75"), ServerConfigCodec.encode(options, values))
    }

    @Test
    fun decodeRoundTrips() {
        assertEquals(values, ServerConfigCodec.decode(options, ServerConfigCodec.encode(options, values)))
    }

    @Test
    fun decodeFallsBackForStaleValues() {
        val decoded = ServerConfigCodec.decode(options, mapOf("genre" to "jazz", "nsfw" to "yes", "minRating" to "500"))
        assertEquals(options.map(SearchForms::defaultOf), decoded)
    }

    @Test
    fun decodeMultiByNames() {
        val diff = SearchOption(
            "diff", "Diff", "multi", def = JsonArray(listOf(JsonPrimitive(false), JsonPrimitive(false), JsonPrimitive(false))),
            values = listOf(OptionValue("easy", "Easy"), OptionValue("normal", "Normal"), OptionValue("hard", "Hard")),
        )
        assertEquals(
            listOf(OptionState.Multi(listOf(true, false, true))),
            ServerConfigCodec.decode(listOf(diff), mapOf("diff" to "easy,hard")),
        )
        assertEquals(
            listOf(SearchForms.defaultOf(diff)),
            ServerConfigCodec.decode(listOf(diff), mapOf("diff" to "easy,expert")),
        )
    }

    private fun store(name: String = "config.json") = ServerConfigStore(java.io.File(tmp.root, name))

    @Test
    fun storeSavesAndReloads() = runBlocking {
        store().save("a.com/x", mapOf("g" to "1"))
        assertEquals(mapOf("a.com/x" to mapOf("g" to "1")), store().values.value)
    }

    @Test
    fun savingEmptyParamsRemovesServer() = runBlocking {
        val s = store()
        s.save("a", mapOf("g" to "1"))
        s.save("a", emptyMap())
        assertTrue(s.values.value.isEmpty())
        assertTrue(store().values.value.isEmpty())
    }

    @Test
    fun clearRemovesServer() = runBlocking {
        val s = store()
        s.save("a", mapOf("g" to "1"))
        s.save("b", mapOf("h" to "2"))
        s.clear("a")
        assertEquals(mapOf("b" to mapOf("h" to "2")), store().values.value)
    }

    @Test
    fun moveRenamesKey() = runBlocking {
        val s = store()
        s.save("a", mapOf("g" to "1"))
        s.move("a", "b")
        assertEquals(mapOf("b" to mapOf("g" to "1")), s.values.value)
        assertEquals(mapOf("b" to mapOf("g" to "1")), store().values.value)
    }

    @Test
    fun corruptFileStartsEmpty() = runBlocking {
        java.io.File(tmp.root, "config.json").writeText("{not json")
        val s = store()
        assertTrue(s.values.value.isEmpty())
        s.save("a", mapOf("g" to "1"))
        assertEquals(mapOf("a" to mapOf("g" to "1")), store().values.value)
    }
}
