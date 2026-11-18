package my.fumui.sairitsu.gameplay

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultLayoutTest {
    @Test
    fun landscapePhonesAreShort() {
        assertTrue(isShortResult(358.dp))
        assertTrue(isShortResult(479.dp))
    }

    @Test
    fun tabletsAreNotShort() {
        assertFalse(isShortResult(480.dp))
        assertFalse(isShortResult(900.dp))
    }

    @Test
    fun tabPageLeavesRoomForTheButtonsAndTabs() {
        assertEquals(246.dp, resultPageHeight(358.dp))
        assertEquals(200.dp, resultPageHeight(312.dp))
    }

    @Test
    fun tabPageStopsGrowingOnTablets() {
        assertEquals(504.dp, resultPageHeight(900.dp))
    }

    @Test
    fun chartTakesTheHeightTheTimingRowsLeaveOver() {
        assertEquals(134.dp, timingChartHeight(246.dp))
        assertEquals(88.dp, timingChartHeight(200.dp))
    }

    @Test
    fun chartStaysReadableOnSmallPhones() {
        assertEquals(68.dp, timingChartHeight(100.dp))
    }

    @Test
    fun chartStopsGrowingOnTablets() {
        assertEquals(260.dp, timingChartHeight(504.dp))
    }
}
