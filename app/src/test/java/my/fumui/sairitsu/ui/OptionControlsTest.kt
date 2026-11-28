package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.model.SearchOption
import org.junit.Assert.assertEquals
import org.junit.Test

class OptionControlsTest {
    private fun slider(min: Double, max: Double, step: Double) =
        SearchOption(query = "q", name = "Q", type = "slider", min = min, max = max, step = step)

    @Test
    fun sliderValueComesOutRoundedToTheStep() {
        val tenths = slider(0.0, 1.0, 0.1)
        assertEquals(0.6, sliderValue(tenths, 0.6f), 0.0) // not 0.6000000000000001
        assertEquals("0.6", formatRating(sliderValue(tenths, 0.6f)))
        assertEquals("0.3", formatRating(sliderValue(tenths, 0.31f)))
        assertEquals(0.15, sliderValue(slider(0.1, 0.2, 0.05), 0.15f), 0.0) // a min a float can't hold
    }

    @Test
    fun sliderValueStaysInTheRange() {
        val tenths = slider(0.0, 1.0, 0.1)
        assertEquals(0.0, sliderValue(tenths, -0.5f), 0.0)
        assertEquals(1.0, sliderValue(tenths, 1.5f), 0.0)
    }
}
