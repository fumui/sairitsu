package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.library.LevelStats
import my.fumui.sairitsu.library.LibraryEntry

/** A downloaded level an item can be previewed on; [lastPlayed] null when it was never played. */
data class PreviewLevel(val entry: LibraryEntry, val lastPlayed: Long?)

/** The downloaded levels of [server]: played ones by last played, newest first, then unplayed ones by when they were added. */
fun previewLevels(server: String, entries: List<LibraryEntry>, stats: Map<LevelKey, LevelStats>): List<PreviewLevel> {
    val levels = entries.filter { it.server == server }.map { PreviewLevel(it, stats[it.key]?.lastPlayed) }
    val (played, unplayed) = levels.partition { it.lastPlayed != null }
    return played.sortedByDescending { it.lastPlayed } + unplayed.sortedByDescending { it.entry.addedAt }
}
