package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.gameplay.LoadProgress
import my.fumui.sairitsu.gameplay.ResourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadBatchTest {
    private fun k(n: String) = LevelKey("s", n)
    private val q = DownloadState.Queued
    private val r = DownloadState.Running(null)
    private val f = DownloadState.Failed("x", null)

    @Test
    fun countsDoneOutOfEverythingQueued() {
        val b = DownloadBatch()
        assertEquals(BatchProgress(0, 3, k("a"), null), b.update(mapOf(k("a") to r, k("b") to q, k("c") to q)))
        assertEquals(BatchProgress(1, 3, k("b"), null), b.update(mapOf(k("b") to r, k("c") to q)))
    }

    @Test
    fun queuedMidBatchGrowsTotal() {
        val b = DownloadBatch()
        b.update(mapOf(k("a") to r, k("b") to q))
        b.update(mapOf(k("b") to r))
        assertEquals(BatchProgress(1, 3, k("b"), null), b.update(mapOf(k("b") to r, k("c") to q)))
    }

    @Test
    fun failedOnlyEndsBatchAndNextStartsFresh() {
        val b = DownloadBatch()
        b.update(mapOf(k("a") to r, k("b") to q))
        assertNull(b.update(mapOf(k("a") to f)))
        assertEquals(BatchProgress(0, 1, k("c"), null), b.update(mapOf(k("a") to f, k("c") to r)))
    }

    @Test
    fun failedCountsAsDone() {
        val b = DownloadBatch()
        b.update(mapOf(k("a") to r, k("b") to q))
        assertEquals(BatchProgress(1, 2, k("b"), null), b.update(mapOf(k("a") to f, k("b") to r)))
    }

    @Test
    fun fractionFromKnownByteTotal() {
        val running = DownloadState.Running(LoadProgress(2, 6, ResourceKind.LEVEL_DATA, 50, 200))
        assertEquals(0.25f, DownloadBatch().update(mapOf(k("a") to running))!!.fraction)
    }
}
