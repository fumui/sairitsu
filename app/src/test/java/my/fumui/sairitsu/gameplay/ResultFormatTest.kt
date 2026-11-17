package my.fumui.sairitsu.gameplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultFormatTest {
    private fun run(arcade: Int, perfect: Int = 0, great: Int = 0, good: Int = 0, miss: Int = 0) =
        PlayResult(Grade.Pass, arcade, 0, 0, perfect, great, good, miss, perfect + great + good + miss)

    @Test
    fun bestDeltaPositive() = assertEquals("+4,210", bestDelta(run(971921), run(967711), partial = false))

    @Test
    fun bestDeltaNegative() = assertEquals("−1,020", bestDelta(run(971921), run(972941), partial = false))

    @Test
    fun bestDeltaEqual() = assertEquals("±0", bestDelta(run(971921), run(971921), partial = false))

    @Test
    fun bestDeltaHiddenWithoutPrevious() = assertNull(bestDelta(run(971921), null, partial = false))

    @Test
    fun bestDeltaHiddenForQuit() = assertNull(bestDelta(run(971921), run(967711), partial = true))

    @Test
    fun fractionsSumToOne() = assertArrayEquals(
        floatArrayOf(663 / 687f, 23 / 687f, 0f, 1 / 687f),
        run(0, perfect = 663, great = 23, miss = 1).judgmentFractions().toFloatArray(),
        1e-6f,
    )

    // A quit run: the bar matches the percentages (shares of the whole chart); the unplayed rest is empty track.
    @Test
    fun fractionsOfAQuitRunAreSharesOfTheWholeChart() = assertArrayEquals(
        floatArrayOf(12 / 500f, 0f, 0f, 3 / 500f),
        PlayResult(Grade.Fail, 0, 0, 0, 12, 0, 0, 3, 500).judgmentFractions().toFloatArray(),
        1e-6f,
    )

    @Test
    fun fractionsOfAnEmptyResultAreZero() =
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), run(0).judgmentFractions().toFloatArray(), 0f)
}
