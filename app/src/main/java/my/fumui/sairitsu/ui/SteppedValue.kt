package my.fumui.sairitsu.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * A numeric range walked in [step]s from [min]. Values are addressed by step index so repeated stepping never drifts,
 * and every value comes out rounded to the step's decimals (9.95, never 9.950000000000001).
 */
class StepScale(val min: Double, val max: Double, step: Double) {
    val step: Double = step.takeIf { it > 0 } ?: 1.0

    /** Decimal places of the step (or of [min], when it has more). */
    val decimals: Int = maxOf(scaleOf(this.step), scaleOf(min))

    /** Index of the last step. */
    val count: Int = ((max - min) / this.step).roundToInt().coerceAtLeast(0)

    fun indexOf(value: Double): Int = ((value - min) / step).roundToInt().coerceIn(0, count)

    fun valueAt(index: Int): Double {
        val raw = min + index.coerceIn(0, count) * step
        return BigDecimal(raw).setScale(decimals, RoundingMode.HALF_UP).toDouble().coerceIn(min, maxOf(min, max))
    }

    fun snap(value: Double): Double = valueAt(indexOf(value))

    private companion object {
        fun scaleOf(v: Double): Int = BigDecimal(v.toString()).stripTrailingZeros().scale().coerceAtLeast(0)
    }
}

/** How a stored value is shown and typed: stored × [multiplier], then [suffix] (100% for 1.0, -120 ms for -0.12). */
data class ValueFormat(val multiplier: Double, val suffix: String) {
    fun format(value: Double, scale: StepScale): String {
        val shown = value * multiplier + 0.0 // + 0.0 turns -0.0 into 0.0
        val decimals = (scale.decimals - log10(multiplier).roundToInt()).coerceAtLeast(0)
        val text = String.format(Locale.ROOT, "%.${decimals}f", shown)
        return (if (text.trimStart('-').all { it == '0' || it == '.' }) text.trimStart('-') else text) + suffix
    }

    /** A typed value in display units, snapped into [scale]; null when it is not a number. */
    fun parse(text: String, scale: StepScale): Double? {
        val number = text.trim().removeSuffix(suffix.trim()).trim().replace(',', '.').toDoubleOrNull() ?: return null
        return scale.snap(number / multiplier)
    }

    companion object {
        val PLAIN = ValueFormat(1.0, "")
        val PERCENT = ValueFormat(100.0, "%")
        val SECONDS_AS_MS = ValueFormat(1000.0, " ms")

        /** The format for an engine option's `unit` text. */
        fun forUnit(unit: String?): ValueFormat = when (unit) {
            null, "" -> PLAIN
            "#PERCENTAGE_UNIT" -> PERCENT
            "#MILLISECOND_UNIT" -> ValueFormat(1.0, " ms")
            "#SECOND_UNIT" -> ValueFormat(1.0, " s")
            "#DECIBEL_UNIT" -> ValueFormat(1.0, " dB")
            "#PIXEL_UNIT" -> ValueFormat(1.0, " px")
            "#BEAT_UNIT" -> ValueFormat(1.0, " beats")
            else -> if (unit.startsWith("#")) PLAIN else ValueFormat(1.0, " $unit")
        }
    }
}
