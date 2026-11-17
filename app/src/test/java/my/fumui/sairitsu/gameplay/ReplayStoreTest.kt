package my.fumui.sairitsu.gameplay

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.GZIPOutputStream

class ReplayStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = ReplayStore(File(tmp.root, "replays"))

    private fun gz(name: String, text: String): File =
        File(tmp.root, name).also { f -> GZIPOutputStream(f.outputStream()).use { it.write(text.toByteArray()) } }

    @Test
    fun saveCopiesBothFilesUnderNewId() = runTest {
        val replay = File(tmp.root, "r.gz").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val config = File(tmp.root, "c.gz").also { it.writeBytes(byteArrayOf(4, 5)) }
        val s = store()
        val id = s.save(replay, config)
        UUID.fromString(id)
        assertArrayEquals(byteArrayOf(1, 2, 3), s.replayFile(id).readBytes())
        assertArrayEquals(byteArrayOf(4, 5), s.configFile(id).readBytes())
    }

    @Test
    fun saveOfMissingSourceThrowsAndLeavesNothing() = runTest {
        val config = File(tmp.root, "c.gz").also { it.writeBytes(byteArrayOf(4, 5)) }
        val s = store()
        try {
            s.save(File(tmp.root, "missing.gz"), config)
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        val left = File(tmp.root, "replays").listFiles().orEmpty()
        assertTrue(left.map { it.name }.toString(), left.isEmpty())
    }

    @Test
    fun nonUuidIdIsRejected() {
        val s = store()
        try {
            s.replayFile("../x")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            s.configFile("../x")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun optionsReadsGzipConfig() = runTest {
        val s = store()
        val id = UUID.randomUUID().toString()
        File(tmp.root, "replays").mkdirs()
        gz("x", """{"options":[1,9.8,0]}""").copyTo(s.configFile(id))
        assertArrayEquals(floatArrayOf(1f, 9.8f, 0f), s.options(id), 0f)
    }

    @Test
    fun optionsOfCorruptFileThrowsIOException() = runTest {
        val s = store()
        val id = UUID.randomUUID().toString()
        File(tmp.root, "replays").mkdirs()
        s.configFile(id).writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            s.options(id)
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        gz("y", "{not json").copyTo(s.configFile(id), overwrite = true)
        try {
            s.options(id)
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    private val keys = mapOf(
        ResourceKind.ENGINE_PLAY_DATA to "p",
        ResourceKind.ENGINE_WATCH_DATA to "w",
        ResourceKind.ENGINE_CONFIGURATION to "c",
        ResourceKind.LEVEL_DATA to "l",
    )

    @Test
    fun engineKeyJoinsThreeKeys() {
        assertEquals("p\nw\nc", engineKey(keys))
        assertEquals(null, engineKey(keys - ResourceKind.ENGINE_WATCH_DATA))
    }

    @Test
    fun replayMatchSame() {
        assertEquals(ReplayMatch.Same, replayMatch("l", engineKey(keys), keys))
    }

    @Test
    fun replayMatchEngineChanged() {
        assertEquals(ReplayMatch.EngineChanged, replayMatch("l", "other", keys))
    }

    @Test
    fun replayMatchChartChangedEvenWithSameEngine() {
        assertEquals(ReplayMatch.ChartChanged, replayMatch("old", engineKey(keys), keys))
    }

    @Test
    fun replayMatchNullLevelDataIsChartChanged() {
        assertEquals(ReplayMatch.ChartChanged, replayMatch(null, engineKey(keys), keys))
    }

    @Test
    fun replayMatchNullEngineWithSameChartIsEngineChanged() {
        assertEquals(ReplayMatch.EngineChanged, replayMatch("l", null, keys))
        assertFalse(replayMatch("l", null, keys) == ReplayMatch.Same)
    }
}
