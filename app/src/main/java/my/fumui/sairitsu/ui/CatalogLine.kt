package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.CatalogSummary
import my.fumui.sairitsu.data.SyncStatus

/** "9,920 levels · synced 5 min ago", the download progress, or the last sync error. */
internal fun catalogLine(catalog: CatalogSummary?): String? {
    catalog ?: return null
    return when (val status = catalog.status) {
        is SyncStatus.Downloading -> "Saving levels… " + "%,d".format(status.done) + (status.total?.let { " / %,d".format(it) } ?: "")
        SyncStatus.Checking -> "%,d levels · checking for new levels…".format(catalog.count)
        is SyncStatus.Failed -> if (catalog.count > 0) "%,d levels · sync failed".format(catalog.count) else "Couldn't save levels: ${status.message}"
        SyncStatus.Idle -> when {
            !catalog.complete -> "%,d levels saved so far · open to continue".format(catalog.count)
            else -> "%,d levels · synced %s".format(catalog.count, syncedAgo(catalog.syncedAt))
        }
    }
}

internal fun syncedAgo(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}
