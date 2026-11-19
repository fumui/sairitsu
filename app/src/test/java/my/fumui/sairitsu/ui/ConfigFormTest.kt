package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.model.OptionValue
import my.fumui.sairitsu.data.model.SearchOption
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigFormTest {
    private val genre = SearchOption(
        "genre", "Genre", "select", def = JsonPrimitive("pop"),
        values = listOf(OptionValue("pop", "Pop"), OptionValue("rock", "Rock")),
    )
    private val nsfw = SearchOption("nsfw", "NSFW", "toggle", def = JsonPrimitive(false))
    private val rating = SearchOption("minRating", "Rating", "slider", def = JsonPrimitive(0), min = 0.0, max = 100.0, step = 1.0)
    private val file = SearchOption("f", "File", "file", def = JsonPrimitive(""))
    private val options = listOf(genre, nsfw, rating, file)

    @Test
    fun ofDecodesStored() {
        val form = ConfigForm.of(options, mapOf("genre" to "rock"))
        assertEquals(OptionState.Select("rock"), form.values[0])
        assertFalse(form.changed)
    }

    @Test
    fun setMarksChanged() {
        val form = ConfigForm.of(options, emptyMap()).set(2, OptionState.Slider(75.0))
        assertTrue(form.changed)
        assertEquals(mapOf("minRating" to "75"), form.params())
    }

    @Test
    fun resetGoesToDefaultsNotSaved() {
        val reset = ConfigForm.of(options, mapOf("genre" to "rock")).reset()
        assertEquals(OptionState.Select("pop"), reset.values[0])
        assertTrue(reset.changed)
        assertTrue(reset.params().isEmpty())
    }

    @Test
    fun settingBackToSavedIsUnchanged() {
        val form = ConfigForm.of(options, mapOf("genre" to "rock"))
            .set(0, OptionState.Select("pop")).set(0, OptionState.Select("rock"))
        assertFalse(form.changed)
    }
}
