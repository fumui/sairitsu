package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.LevelKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunHistoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val k = LevelKey("s", "a")

    private fun history(dir: File = tmp.root) = FileRunHistory(dir)

    private fun run(arcade: Int, accuracy: Int = 0, at: Long? = 1) =
        RunRecord(at, PlayResult(Grade.Pass, arcade, accuracy, 0, 0, 0, 0, 0, 0))

    private fun line(r: RunRecord) = Json.encodeToString(RunRecord.serializer(), r)

    private fun onlyFile(): File = tmp.root.listFiles()!!.single()

    @Test
    fun noFileReturnsNull() = runTest {
        assertNull(history().runs(k))
    }

    @Test
    fun appendThenReadInOrder() = runTest {
        val h = history()
        h.append(k, run(1, at = 10))
        h.append(k, run(2, at = 20))
        assertEquals(listOf(run(1, at = 10), run(2, at = 20)), h.runs(k))
    }

    @Test
    fun seedWrittenOnlyWhenNoHistory() = runTest {
        val h = history()
        val best = run(999).result
        h.append(k, run(1), seed = best)
        h.append(k, run(2), seed = run(5).result)
        assertEquals(listOf(RunRecord(null, best), run(1), run(2)), h.runs(k))
    }

    @Test
    fun malformedLinesSkipped() = runTest {
        val h = history()
        h.append(k, run(1))
        onlyFile().writeText("${line(run(1))}\n{garbage\n\n${line(run(2))}\n")
        assertEquals(listOf(run(1), run(2)), h.runs(k))
    }

    @Test
    fun appendAfterTornLineStartsNewLine() = runTest {
        val h = history()
        h.append(k, run(1))
        onlyFile().appendText("{\"at\":5,\"res")
        h.append(k, run(2))
        assertEquals(listOf(run(1), run(2)), h.runs(k))
    }

    @Test
    fun keysWithSlashesAndColonsGetSeparateFiles() = runTest {
        val h = history()
        val first = LevelKey("https://h:8080/x", "a")
        val second = LevelKey("https://h:8080", "x/a")
        h.append(first, run(1))
        h.append(second, run(2))
        assertEquals(listOf(run(1)), h.runs(first))
        assertEquals(listOf(run(2)), h.runs(second))
        assertEquals(2, tmp.root.listFiles()!!.size)
    }

    @Test
    fun oldLinesDecodeWithNullReplayFields() = runTest {
        val h = history()
        h.append(k, run(1))
        onlyFile().writeText("""{"at":1,"result":${Json.encodeToString(PlayResult.serializer(), run(7).result)}}""" + "\n")
        val r = h.runs(k)!!.single()
        assertEquals(1L, r.at)
        assertNull(r.replay)
        assertNull(r.levelData)
        assertNull(r.engine)
    }

    @Test
    fun newFieldsRoundTrip() = runTest {
        val h = history()
        val r = run(3).copy(replay = "id", levelData = "ld", engine = "e")
        h.append(k, r)
        assertEquals(listOf(r), h.runs(k))
    }

    @Test
    fun attachReplaySetsTheMatchingRecord() = runTest {
        val h = history()
        for (i in 1..3) h.append(k, run(i * 10, at = i.toLong()))
        assertTrue(h.attachReplay(k, 2, "id"))
        assertEquals(
            listOf(run(10, at = 1), run(20, at = 2).copy(replay = "id"), run(30, at = 3)),
            h.runs(k),
        )
    }

    @Test
    fun attachReplayWithoutMatchLeavesFileUnchanged() = runTest {
        val h = history()
        for (i in 1..3) h.append(k, run(i, at = i.toLong()))
        val before = onlyFile().readBytes()
        assertFalse(h.attachReplay(k, 9, "id"))
        assertArrayEquals(before, onlyFile().readBytes())
    }

    @Test
    fun attachReplayKeepsSkippingTornLines() = runTest {
        val h = history()
        h.append(k, run(1, at = 1))
        h.append(k, run(2, at = 2))
        onlyFile().appendText("{\"at\":5,\"res")
        assertTrue(h.attachReplay(k, 2, "id"))
        assertEquals(listOf(run(1, at = 1), run(2, at = 2).copy(replay = "id")), h.runs(k))
    }

    private fun List<RunRecord>.summary() = map { "${it.result.arcadeScore}/${it.result.accuracyScore}/${it.at}" }

    @Test
    fun sortTieBreaks() {
        val runs = listOf(run(900, 5, 1), run(900, 7, 2), run(950, 1, 3), run(900, 7, 4))
        assertEquals(listOf("950/1/3", "900/7/4", "900/7/2", "900/5/1"), sortRuns(runs, RunSort.Arcade).summary())
        assertEquals(listOf("900/7/4", "900/7/2", "900/5/1", "950/1/3"), sortRuns(runs, RunSort.Accuracy).summary())
    }

    @Test
    fun dateSortPutsUndatedLast() {
        val runs = listOf(run(1, at = 1), run(2, at = null), run(3, at = 3))
        assertEquals(listOf(3L, 1L, null), sortRuns(runs, RunSort.Date).map { it.at })
    }
}
