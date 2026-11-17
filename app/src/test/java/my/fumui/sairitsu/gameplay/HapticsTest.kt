package my.fumui.sairitsu.gameplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HapticsTest {
    @Test
    fun hapticLevelsMap() {
        assertNull(Haptics.spec(0, 36))
        assertNull(Haptics.spec(0, 26))
        assertEquals(HapticSpec(Haptics.EFFECT_TICK, null), Haptics.spec(1, 29))
        assertEquals(HapticSpec(Haptics.EFFECT_CLICK, null), Haptics.spec(2, 36))
        assertEquals(HapticSpec(Haptics.EFFECT_HEAVY_CLICK, null), Haptics.spec(3, 36))
        assertEquals(HapticSpec(null, 60), Haptics.spec(4, 36))
        // API < 29: one-shots of 10/20/30/60 ms.
        assertEquals(listOf(10L, 20L, 30L, 60L), (1..4).map { Haptics.spec(it, 28)!!.oneShotMs })
        assertEquals(listOf(null, null, null, null), (1..4).map { Haptics.spec(it, 28)!!.predefined })
    }

    @Test
    fun effectIdsAreTheFrameworkConstants() {
        // VibrationEffect.EFFECT_CLICK = 0, EFFECT_TICK = 2, EFFECT_HEAVY_CLICK = 5.
        assertEquals(listOf(2, 0, 5), listOf(Haptics.EFFECT_TICK, Haptics.EFFECT_CLICK, Haptics.EFFECT_HEAVY_CLICK))
    }
}
