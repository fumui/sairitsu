package my.fumui.sairitsu.ui

import my.fumui.sairitsu.gameplay.Grade
import my.fumui.sairitsu.gameplay.PlayResult
import my.fumui.sairitsu.gameplay.ReplayRef
import my.fumui.sairitsu.gameplay.RunRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class RunListTest {
    @Test
    fun dateIsLocalMinutes() {
        assertEquals("2025-11-16 21:04", runDateText(1_763_327_040_000L, ZoneOffset.UTC))
    }

    @Test
    fun undatedIsDash() {
        assertEquals("—", runDateText(null))
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

    @Test
    fun judgmentsArePerfectGreatGoodMiss() {
        assertEquals("681 / 6 / 0 / 0", judgmentText(PlayResult(Grade.FullCombo, 0, 0, 687, 681, 6, 0, 0, 687)))
    }
}
