package my.fumui.sairitsu.gameplay

import android.os.Build
import android.os.VibrationEffect

/** What to play for a haptic level: a predefined effect id (API 29+) or a one-shot of [oneShotMs]. */
data class HapticSpec(val predefined: Int?, val oneShotMs: Long?)

object Haptics {
    // VibrationEffect.EFFECT_TICK / EFFECT_CLICK / EFFECT_HEAVY_CLICK (API 29); the ids are fixed, so the mapping
    // stays a pure function.
    const val EFFECT_TICK = 2
    const val EFFECT_CLICK = 0
    const val EFFECT_HEAVY_CLICK = 5

    /** The mapping: 1 tick, 2 click, 3 heavy click, 4 a 60 ms one-shot; below API 29 one-shots of 10/20/30/60 ms. Null for 0 (or unknown). */
    fun spec(level: Int, sdk: Int): HapticSpec? = when (level) {
        1 -> if (sdk >= 29) HapticSpec(EFFECT_TICK, null) else HapticSpec(null, 10)
        2 -> if (sdk >= 29) HapticSpec(EFFECT_CLICK, null) else HapticSpec(null, 20)
        3 -> if (sdk >= 29) HapticSpec(EFFECT_HEAVY_CLICK, null) else HapticSpec(null, 30)
        4 -> HapticSpec(null, 60)
        else -> null
    }

    fun effect(level: Int): VibrationEffect? {
        val spec = spec(level, Build.VERSION.SDK_INT) ?: return null
        return spec.predefined?.let { VibrationEffect.createPredefined(it) }
            ?: VibrationEffect.createOneShot(spec.oneShotMs!!, VibrationEffect.DEFAULT_AMPLITUDE)
    }
}
