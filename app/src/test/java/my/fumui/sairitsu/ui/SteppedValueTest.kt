package my.fumui.sairitsu.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteppedValueTest {
    private val noteSpeed = StepScale(1.0, 12.0, 0.05)
    private val alpha = StepScale(0.0, 1.0, 0.05)
    private val offset = StepScale(-0.5, 0.5, 0.005)

    @Test
    fun snapsToCleanDecimals() {
        assertEquals(9.95, noteSpeed.snap(9.950000000000001), 0.0)
        assertEquals(0.6, alpha.snap(0.6000000000000001), 0.0)
        assertEquals(0.6, alpha.valueAt(12), 0.0)
        assertEquals(-0.115, offset.snap(-0.1149), 0.0)
    }

    @Test
    fun clampsToBounds() {
        assertEquals(12.0, noteSpeed.snap(40.0), 0.0)
        assertEquals(1.0, noteSpeed.snap(-3.0), 0.0)
        assertEquals(1.0, noteSpeed.valueAt(-1), 0.0)
        assertEquals(12.0, noteSpeed.valueAt(noteSpeed.count + 5), 0.0)
    }

    @Test
    fun stepsByIndex() {
        assertEquals(10.0, noteSpeed.valueAt(noteSpeed.indexOf(9.95) + 1), 0.0)
        assertEquals(220, noteSpeed.count)
    }

    @Test
    fun formatsWithStepPrecision() {
        assertEquals("9.95", ValueFormat.PLAIN.format(9.95, noteSpeed))
        assertEquals("10.00", ValueFormat.PLAIN.format(10.0, noteSpeed))
        assertEquals("60%", ValueFormat.PERCENT.format(0.6, alpha))
        assertEquals("100%", ValueFormat.PERCENT.format(1.0, StepScale(0.5, 2.0, 0.05)))
        assertEquals("-115 ms", ValueFormat.SECONDS_AS_MS.format(-0.115, offset))
        assertEquals("0 ms", ValueFormat.SECONDS_AS_MS.format(-0.0, offset))
        assertEquals("12.5%", ValueFormat.PERCENT.format(0.125, StepScale(0.0, 1.0, 0.005)))
    }

    @Test
    fun parsesTypedValuesInDisplayUnits() {
        assertEquals(0.6, ValueFormat.PERCENT.parse("60", alpha)!!, 0.0)
        assertEquals(0.6, ValueFormat.PERCENT.parse(" 61 % ", alpha)!!, 0.0)
        assertEquals(9.95, ValueFormat.PLAIN.parse("9,95", noteSpeed)!!, 0.0)
        assertEquals(-0.12, ValueFormat.SECONDS_AS_MS.parse("-120ms", offset)!!, 0.0)
        assertEquals(12.0, ValueFormat.PLAIN.parse("99", noteSpeed)!!, 0.0)
        assertNull(ValueFormat.PLAIN.parse("abc", noteSpeed))
    }

    @Test
    fun picksFormatFromEngineUnit() {
        assertEquals(ValueFormat.PERCENT, ValueFormat.forUnit("#PERCENTAGE_UNIT"))
        assertEquals(" ms", ValueFormat.forUnit("#MILLISECOND_UNIT").suffix)
        assertEquals(ValueFormat.PLAIN, ValueFormat.forUnit(null))
        assertEquals(" px", ValueFormat.forUnit("px").suffix)
    }
}
