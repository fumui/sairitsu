package my.fumui.sairitsu.gameplay

import java.io.File

/** What the native side reports about the replay of the run that just ended (codes 0..3 of `replayStatus`). */
enum class ReplayStatus {
    None,
    Writing,
    Ready,
    Failed,
    ;

    companion object {
        fun fromCode(code: Int): ReplayStatus = entries.getOrElse(code) { Failed }
    }
}

/** The temporary files a Play run writes its replay to; [base] is what the native side is given. */
class ReplayTemp(val replay: File, val config: File) {
    /** The replay path without the `.json.gz` suffix: the native side appends `.json.gz` and `.config.gz`. */
    val base: File get() = File(replay.path.removeSuffix(".json.gz"))
}

/** The result screen's "Save replay" state. */
sealed interface ReplaySave {
    /** Quit, no temp files, or Watch: nothing to save. */
    data object Unavailable : ReplaySave

    /** The run ended and its replay is still being written. */
    data object Preparing : ReplaySave

    data object Ready : ReplaySave

    data object Saving : ReplaySave

    data object Saved : ReplaySave

    data class Failed(val message: String) : ReplaySave
}
