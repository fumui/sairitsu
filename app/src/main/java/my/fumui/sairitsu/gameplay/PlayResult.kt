package my.fumui.sairitsu.gameplay

import kotlinx.serialization.Serializable

/** The grade of a Play run. The native ordinals (see [PlayState.RESULT]) are 0 all perfect .. 3 fail. */
enum class Grade(val label: String) {
    AllPerfect("All perfect"),
    FullCombo("Full combo"),
    Pass("Pass"),
    Fail("Fail"),
}

/** A finished Play run (see [PlayState]); stored as JSON for the best result per level. */
@Serializable
data class PlayResult(
    val grade: Grade,
    val arcadeScore: Int,
    val accuracyScore: Int,
    val combo: Int,
    val perfect: Int,
    val great: Int,
    val good: Int,
    val miss: Int,
    val totalCount: Int,
) {
    companion object {
        /** Reads the result out of [PlayState.RESULT]'s layout in [out]. */
        fun fromNative(out: FloatArray): PlayResult {
            fun at(i: Int) = out[PlayState.RESULT + i].toInt()
            return PlayResult(
                Grade.entries[at(0).coerceIn(0, Grade.entries.size - 1)],
                at(1), at(2), at(3), at(4), at(5), at(6), at(7), at(8),
            )
        }
    }
}

/** A score as shown in the app: no leading zeros, thousands grouped with commas ("983,430"). */
fun scoreText(score: Int): String = "%,d".format(java.util.Locale.ROOT, score)

fun PlayResult.arcadeText(): String = scoreText(arcadeScore)

fun PlayResult.accuracyText(): String = scoreText(accuracyScore)

/** [count] as a percentage of [PlayResult.totalCount], one decimal ("94.2%"); 0.0% when the total is 0. */
fun PlayResult.percent(count: Int): String =
    "%.1f%%".format(java.util.Locale.ROOT, if (totalCount > 0) count * 100.0 / totalCount else 0.0)
