package my.fumui.sairitsu.gameplay

import java.util.Locale
import kotlin.math.abs

/**
 * The arcade score's difference from [previous] for the result screen: "+4,210", "−1,020" (U+2212) or "±0"; null when
 * there is no previous best or the run was quit ([partial]).
 */
fun bestDelta(result: PlayResult, previous: PlayResult?, partial: Boolean): String? {
    if (previous == null || partial) return null
    val diff = result.arcadeScore.toLong() - previous.arcadeScore
    val digits = "%,d".format(Locale.ROOT, abs(diff))
    return when {
        diff > 0 -> "+$digits"
        diff < 0 -> "−$digits"
        else -> "±0"
    }
}

/**
 * Perfect, great, good and miss as shares of the whole chart (the judgment bar), like [percent]: a quit run leaves the
 * unplayed rest empty. All 0 when nothing was judged.
 */
fun PlayResult.judgmentFractions(): List<Float> {
    val counts = listOf(perfect, great, good, miss)
    val whole = maxOf(totalCount, counts.sum())
    return if (whole <= 0) List(4) { 0f } else counts.map { it.toFloat() / whole }
}
