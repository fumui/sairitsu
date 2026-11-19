package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.LevelItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistSaverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = ServerAddress.parse("sonolus.sbuga.com")!!

    private fun key(name: String) = LevelKey(server.linkAddress, name)

    private fun level(name: String) = LevelItem(name = name, title = name)

    private val library = HashSet<LevelKey>()
    private val downloaded = mutableListOf<String>()
    private val collections by lazy { CollectionStore(File(tmp.root, "collections.json")) { library } }
    private val saver by lazy {
        PlaylistSaver(collections, download = { _, _, level -> downloaded += level.name }, libraryKeys = { library })
    }

    @Test
    fun newCollectionGetsDownloadedNowAndPendingRest() = runBlocking {
        library += key("a")
        val id = saver.save(server, "Sbuga", "Pack", listOf(level("a"), level("b"), level("c"))).getOrThrow()
        val collection = collections.get(id)!!
        assertEquals("Pack", collection.name)
        assertEquals(listOf(key("a")), collection.levels)
        assertEquals(listOf("b", "c"), downloaded)
        assertEquals(setOf(id), collections.pending.value[key("b")])
        assertEquals(setOf(id), collections.pending.value[key("c")])
    }

    @Test
    fun existingSameNameReusedIgnoringCase() = runBlocking {
        val existing = collections.create("summer event pack")
        val id = saver.save(server, "Sbuga", "Summer Event Pack", listOf(level("a"))).getOrThrow()
        assertEquals(existing.id, id)
        assertEquals(1, collections.collections.value.size)
    }

    @Test
    fun secondRunQueuesOnlyMissing() = runBlocking {
        val levels = listOf(level("a"), level("b"), level("c"))
        saver.save(server, "Sbuga", "Pack", levels).getOrThrow()
        library += key("b")
        downloaded.clear()
        val id = saver.save(server, "Sbuga", "Pack", levels).getOrThrow()
        assertEquals(listOf("a", "c"), downloaded)
        assertTrue(key("b") in collections.get(id)!!.levels)
    }

    @Test
    fun alreadyQueuedNotDuplicated() = runBlocking {
        val levels = listOf(level("a"), level("b"))
        val first = saver.save(server, "Sbuga", "Pack", levels).getOrThrow()
        val second = saver.save(server, "Sbuga", "Pack", levels).getOrThrow()
        assertEquals(first, second)
        assertEquals(1, collections.collections.value.size)
        assertEquals(setOf(first), collections.pending.value[key("a")])
    }

    @Test
    fun saveFailureIsResult() = runBlocking {
        // A directory where the collection file's temp copy goes makes the write fail, like a full disk.
        File(tmp.root, "collections.json.tmp").mkdirs()
        val result = saver.save(server, "Sbuga", "Pack", listOf(level("a")))
        assertTrue(result.isFailure)
    }

    @Test
    fun playlistCollectionNameFallsBack() {
        assertEquals("p-1", playlistCollectionName("   ", "p-1"))
        assertEquals("p-1", playlistCollectionName("x".repeat(200), "p-1"))
        assertEquals("Pack", playlistCollectionName("  Pack ", "p-1"))
    }

    @Test
    fun progressStates() {
        val levels = listOf(level("a"), level("b"))
        val s = server.linkAddress
        assertEquals(PlaylistProgress(0, 2, PlaylistRowState.NoneSaved), playlistProgress(s, levels, emptySet(), emptySet()))
        assertEquals(PlaylistProgress(1, 2, PlaylistRowState.PartlySaved), playlistProgress(s, levels, setOf(key("a")), emptySet()))
        assertEquals(
            PlaylistProgress(1, 2, PlaylistRowState.Downloading),
            playlistProgress(s, levels, setOf(key("a")), setOf(key("b"))),
        )
        assertEquals(PlaylistProgress(2, 2, PlaylistRowState.AllSaved), playlistProgress(s, levels, setOf(key("a"), key("b")), emptySet()))
        assertEquals(PlaylistProgress(0, 0, PlaylistRowState.NoneSaved), playlistProgress(s, emptyList(), emptySet(), emptySet()))
    }
}
