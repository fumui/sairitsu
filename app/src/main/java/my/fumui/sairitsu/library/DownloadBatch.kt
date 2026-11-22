package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.text.Localizer

/** What the download notification shows; [current] is the Running level, [fraction] its byte progress when known. */
data class BatchProgress(val done: Int, val total: Int, val current: LevelKey?, val fraction: Float?) {
    /** "done of total · title"; [title] is the running level's raw server title, null when unknown (its name shows instead). */
    fun text(title: String?, localizer: Localizer): String {
        val count = "$done of $total"
        val level = current ?: return count
        return "$count · ${title?.let(localizer::text) ?: level.level}"
    }
}

/**
 * The downloads since the notification appeared: every level seen Queued or Running counts in the total, and is done
 * once it is neither (finished, failed or cancelled). The batch ends when nothing is Queued or Running.
 */
class DownloadBatch {
    private val keys = HashSet<LevelKey>()

    /** Folds in the latest states; null when nothing is Queued or Running (the next update starts a new batch). */
    fun update(states: Map<LevelKey, DownloadState>): BatchProgress? {
        val active = states.filterValues { it !is DownloadState.Failed }
        if (active.isEmpty()) {
            keys.clear()
            return null
        }
        keys += active.keys
        val running = active.entries.firstOrNull { it.value is DownloadState.Running }
        val progress = (running?.value as? DownloadState.Running)?.progress
        val fraction = progress?.totalBytes?.takeIf { it > 0 }?.let { progress.bytes.toFloat() / it }
        return BatchProgress(keys.count { it !in active }, keys.size, running?.key, fraction)
    }
}
