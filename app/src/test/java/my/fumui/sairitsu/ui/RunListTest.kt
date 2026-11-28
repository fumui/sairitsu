package my.fumui.sairitsu.ui

import my.fumui.sairitsu.gameplay.Grade
import my.fumui.sairitsu.gameplay.PlayResult
import my.fumui.sairitsu.gameplay.ReplayRef
import my.fumui.sairitsu.gameplay.RunRecord
import my.fumui.sairitsu.gameplay.RunSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class RunListTest {
    private val now = 1_763_327_040_000L // 2025-11-16 21:04 UTC

    @Test
    fun agoText() {
        assertEquals("—", runAgoText(null, now))
        assertEquals("just now", runAgoText(now - 59_000, now))
        assertEquals("just now", runAgoText(now + 180_000, now)) // clock set back
        assertEquals("1m ago", runAgoText(now - 60_000, now))
        assertEquals("59m ago", runAgoText(now - 3_599_000, now))
        assertEquals("3h ago", runAgoText(now - 3 * 3_600_000L - 59_000, now))
        assertEquals("1d ago", runAgoText(now - 24 * 3_600_000L, now))
        assertEquals("27d ago", runAgoText(now - 27 * 86_400_000L, now))
        assertEquals("2025-10-19", runAgoText(now - 28 * 86_400_000L, now, ZoneOffset.UTC))
    }

    @Test
    fun scoreFollowsTheSort() {
        val r = PlayResult(Grade.FullCombo, 980_318, 982_511, 690, 642, 48, 0, 0, 690)
        assertEquals("980,318", runScoreText(r, RunSort.Arcade))
        assertEquals("980,318", runScoreText(r, RunSort.Date))
        assertEquals("982,511", runScoreText(r, RunSort.Accuracy))
        assertEquals("Arcade", runScoreHeader(RunSort.Arcade))
        assertEquals("Arcade", runScoreHeader(RunSort.Date))
        assertEquals("Accuracy", runScoreHeader(RunSort.Accuracy))
    }

    @Test
    fun replayRefOnlyForRunsWithReplay() {
        val result = PlayResult(Grade.FullCombo, 0, 0, 687, 681, 6, 0, 0, 687)
        assertNull(replayRef(RunRecord(1L, result)))
        assertEquals(
            ReplayRef("abc", "ld", "eng"),
            replayRef(RunRecord(1L, result, replay = "abc", levelData = "ld", engine = "eng")),
        )
    }
}
