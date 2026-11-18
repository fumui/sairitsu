package my.fumui.sairitsu.gameplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingStatsTest {
    private val perfect = 1f
    private val great = 2f
    private val good = 3f
    private val miss = 0f

    /** Native pairs from judgment to error in ms. */
    private fun pairs(vararg notes: Pair<Float, Double>): FloatArray =
        notes.flatMap { (judgment, ms) -> listOf(judgment, (ms / 1000.0).toFloat()) }.toFloatArray()

    private fun stats(vararg notes: Pair<Float, Double>): TimingStats = TimingStats.fromNative(pairs(*notes))!!

    private fun repeated(count: Int, ms: Double): TimingStats =
        stats(*Array(count) { perfect to ms })

    @Test
    fun filtersMissesAutoJudgedAndNonFinite() {
        val notes = pairs(perfect to -10.0, great to 10.0, miss to 116.7, perfect to 0.0) +
            floatArrayOf(good, Float.NaN, good, Float.POSITIVE_INFINITY)
        assertEquals(2, TimingStats.fromNative(notes)!!.count)
    }

    @Test
    fun averageAndUnstableRate() {
        val s = stats(perfect to -10.0, great to 10.0)
        assertEquals(0.0, s.averageMs, 1e-3)
        assertEquals(100.0, s.unstableRate, 1e-3)
    }

    @Test
    fun noTimedHitsIsNull() {
        assertNull(TimingStats.fromNative(pairs(miss to 83.3)))
        assertNull(TimingStats.fromNative(FloatArray(0)))
    }

    @Test
    fun correctionRoundsAverage() {
        assertEquals(-2, repeated(3, -2.18).correctionMs)
    }

    @Test
    fun canApplyNeedsTwentyHitsAndACorrection() {
        assertFalse(repeated(19, -5.0).canApply)
        assertTrue(repeated(20, -5.0).canApply)
        assertFalse(repeated(20, 0.2).canApply)
    }

    @Test
    fun axisHalfWidth() {
        assertEquals(100, stats(perfect to 40.0).halfWidthMs)
        assertEquals(150, stats(good to -107.0).halfWidthMs)
        assertEquals(200, stats(good to 320.0).halfWidthMs)
    }

    @Test
    fun binsAreCenteredOnZero() {
        val s = stats(perfect to 1.9, perfect to 40.0)
        assertEquals(51, s.bins.size)
        assertEquals(-100, s.bins.first().centerMs)
        assertEquals(100, s.bins.last().centerMs)
        assertEquals(1, s.bins.single { it.centerMs == 0 }.perfect)
        val wide = stats(good to -320.0)
        assertEquals(1, wide.bins.single { it.centerMs == -200 }.good)
    }

    @Test
    fun binsStackByJudgment() {
        val bin = stats(perfect to 8.0, great to 8.0).bins.single { it.centerMs == 8 }
        assertEquals(1, bin.perfect)
        assertEquals(1, bin.great)
        assertEquals(0, bin.good)
    }

    @Test
    fun averageTextSaysEarlyOrLate() {
        assertEquals("2.2 ms early", repeated(3, -2.18).averageText)
        assertEquals("4.5 ms late", repeated(3, 4.5).averageText)
        assertEquals("0.0 ms", stats(perfect to -10.0, perfect to 10.0).averageText)
    }

    @Test
    fun correctionTextDescribesTheRun() {
        assertEquals("You hit 2 ms early on average.", correctionText(-2))
        assertEquals("You hit 7 ms late on average.", correctionText(7))
    }

    @Test
    fun offsetTextIsWholeMilliseconds() {
        assertEquals("-2 ms", offsetText(-0.002f))
        assertEquals("0 ms", offsetText(0f))
    }

    @Test
    fun suggestedOffsetAddsAndClamps() {
        assertEquals(-0.002f, suggestedOffset(0f, -2), 0f)
        assertEquals(0.5f, suggestedOffset(0.499f, 5), 0f)
        assertEquals(-0.5f, suggestedOffset(-0.499f, -5), 0f)
    }
}
