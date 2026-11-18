package my.fumui.sairitsu.gameplay

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One bar of the timing chart: the timed hits within 2 ms of [centerMs], by judgment. */
data class TimingBin(val centerMs: Int, val perfect: Int, val great: Int, val good: Int) {
    val total: Int get() = perfect + great + good
}

/**
 * How a run's taps landed against the notes. Only timed hits count: Perfect, Great or Good with a real accuracy.
 * Misses report a fixed value and auto-judged notes report exactly 0, so neither says anything about the player.
 * Errors are in ms, negative early.
 */
class TimingStats private constructor(private val hits: List<Hit>) {
    private class Hit(val judgment: Int, val errorMs: Double)

    val count: Int = hits.size

    val averageMs: Double = hits.sumOf { it.errorMs } / count

    /** 10 times the population standard deviation of the errors. */
    val unstableRate: Double = 10 * sqrt(hits.sumOf { (it.errorMs - averageMs).let { d -> d * d } } / count)

    /** What to add to an input offset to centre these hits, in whole ms. */
    val correctionMs: Int = averageMs.roundToInt()

    val canApply: Boolean = count >= MIN_HITS_TO_APPLY && correctionMs != 0

    /** "2.2 ms early", "4.5 ms late", or "0.0 ms" when it rounds to nothing. */
    val averageText: String = run {
        val text = "%.1f ms".format(Locale.ROOT, abs(averageMs))
        when {
            text == "0.0 ms" -> text
            averageMs < 0 -> "$text early"
            else -> "$text late"
        }
    }

    /** The chart's axis runs from -[halfWidthMs] to +[halfWidthMs]. */
    val halfWidthMs: Int = run {
        val widest = hits.maxOf { abs(it.errorMs) }
        (ceil(widest / AXIS_STEP_MS).toInt() * AXIS_STEP_MS).coerceIn(MIN_HALF_WIDTH_MS, MAX_HALF_WIDTH_MS)
    }

    /** Bars [BIN_WIDTH_MS] wide with one centred on 0; hits past the axis count in the outermost bar. */
    val bins: List<TimingBin> = run {
        val size = halfWidthMs * 2 / BIN_WIDTH_MS + 1
        val counts = Array(size) { IntArray(3) }
        for (hit in hits) {
            val index = ((hit.errorMs + halfWidthMs) / BIN_WIDTH_MS).roundToInt().coerceIn(0, size - 1)
            counts[index][hit.judgment - 1]++
        }
        counts.mapIndexed { i, (perfect, great, good) ->
            TimingBin(i * BIN_WIDTH_MS - halfWidthMs, perfect, great, good)
        }
    }

    companion object {
        const val MIN_HITS_TO_APPLY = 20
        private const val BIN_WIDTH_MS = 4
        private const val AXIS_STEP_MS = 50
        private const val MIN_HALF_WIDTH_MS = 100
        private const val MAX_HALF_WIDTH_MS = 200

        /** From [LevelRuntime.playTimings]' judgment, accuracy (s) pairs; null when the run has no timed hits. */
        fun fromNative(pairs: FloatArray): TimingStats? {
            val hits = (0 until pairs.size / 2).mapNotNull { i ->
                val judgment = pairs[i * 2]
                val accuracy = pairs[i * 2 + 1]
                val timed = (judgment == 1f || judgment == 2f || judgment == 3f) && accuracy != 0f && accuracy.isFinite()
                if (timed) Hit(judgment.toInt(), accuracy * 1000.0) else null
            }
            return if (hits.isEmpty()) null else TimingStats(hits)
        }
    }
}

/** The Apply dialog's summary of a run whose hits need [correctionMs]. */
fun correctionText(correctionMs: Int): String =
    "You hit ${abs(correctionMs)} ms ${if (correctionMs < 0) "early" else "late"} on average."

/** An offset in seconds as the app writes it: "-2 ms". */
fun offsetText(seconds: Float): String = "${(seconds * 1000f).roundToInt()} ms"

/**
 * The input offset (s) that centres hits measured under [current]: touches are judged at `touch - offset`, so early
 * hits (a negative correction) need a lower offset. Kept in whole ms within the slider's ±500 ms.
 */
fun suggestedOffset(current: Float, correctionMs: Int): Float =
    ((current * 1000f).roundToInt() + correctionMs).coerceIn(-500, 500) / 1000f
