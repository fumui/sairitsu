package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.gameplay.LoadProgress
import my.fumui.sairitsu.gameplay.ResourceKind
import my.fumui.sairitsu.library.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadButtonStateTest {
    private val progress = LoadProgress(3, 15, ResourceKind.SKIN_DATA, 10, 100)

    @Test
    fun mapsEachState() {
        assertEquals(DownloadButtonState.NotDownloaded, downloadButtonState(false, null))
        assertEquals(DownloadButtonState.InProgress(null), downloadButtonState(false, DownloadState.Queued))
        assertEquals(DownloadButtonState.InProgress(progress), downloadButtonState(false, DownloadState.Running(progress)))
        assertEquals(DownloadButtonState.Failed("x"), downloadButtonState(false, DownloadState.Failed("x", null)))
        assertEquals(DownloadButtonState.Downloaded, downloadButtonState(true, null))
    }

    @Test
    fun levelsToQueueSkipsDownloadedAndInProgress() {
        val levels = listOf("a", "b", "c", "d", "e", "e").map { LevelItem(name = it, title = it) }
        val result = levelsToQueue(
            levels,
            downloaded = setOf("a"),
            states = mapOf(
                "b" to DownloadState.Queued,
                "c" to DownloadState.Running(progress),
                "d" to DownloadState.Failed("x", null),
            ),
        )
        assertEquals(listOf("d", "e"), result.map { it.name })
    }

    @Test
    fun downloadedWinsOverFailed() {
        assertEquals(DownloadButtonState.Downloaded, downloadButtonState(true, DownloadState.Running(progress)))
    }
}
