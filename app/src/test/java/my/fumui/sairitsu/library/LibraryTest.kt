package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.gameplay.LoadedResources
import my.fumui.sairitsu.gameplay.ResourceCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = ServerAddress.parse("sonolus.sbuga.com")!!

    private fun setup(): Triple<Library, ResourceCache, FakeDownloadSource> {
        val cache = ResourceCache(tmp.newFolder("cache"))
        val library = Library(LibraryIndex(File(tmp.root, "library.json")), cache, now = { 42L })
        return Triple(library, cache, FakeDownloadSource(cache))
    }

    @Test
    fun entryFromReadsDetails() = runBlocking {
        val (library, _, source) = setup()
        val loaded = source.load(server, "a") {}
        val e = library.entryFrom(server, "Sbuga", loaded, "cover-key", null)
        assertEquals("sonolus.sbuga.com", e.server)
        assertEquals("Sbuga", e.serverTitle)
        assertEquals("a", e.name)
        assertEquals("Title a", e.title)
        assertEquals(24.0, e.rating, 0.0)
        assertEquals(listOf("Expert", "#VIRTUAL"), e.tags)
        assertEquals("rush", e.engineName)
        assertEquals("Next RUSH", e.engineTitle)
        assertEquals(42L, e.addedAt)
        assertEquals(loaded.keys, e.resourceKeys)
        assertEquals("details-a", e.detailsKey)
        assertEquals("cover-key", e.coverKey)
        assertNull(e.previewKey)
        assertEquals("About a", library.readDetails(e)?.description)
    }

    @Test
    fun entryKeepsTheServerScheme() = runBlocking {
        val (library, _, source) = setup()
        val http = ServerAddress.parse("http://192.168.1.5:3000")!!
        val e = library.entryFrom(http, "Local", source.load(http, "a") {}, null, null)
        assertEquals("192.168.1.5:3000", e.server)
        assertEquals("http://192.168.1.5:3000", e.serverUrl)
        assertEquals("https://sonolus.sbuga.com", entry("x").serverUrl)
    }

    @Test
    fun addRejectsEntryWithMissingFiles() = runBlocking {
        val (library, cache, source) = setup()
        val loaded = source.load(server, "a") {}
        cache.delete(setOf("a-13"))
        try {
            library.add(library.entryFrom(server, "S", loaded, null, null))
            fail("expected MissingFiles")
        } catch (e: MissingFiles) {
            assertEquals(setOf("a-13"), e.keys)
        }
        assertTrue(library.index.entries.value.isEmpty())
    }

    @Test
    fun deleteRemovesUnsharedFilesFromCache() = runBlocking {
        val (library, cache, source) = setup()
        library.add(library.entryFrom(server, "S", source.load(server, "a") {}, null, null))
        library.add(library.entryFrom(server, "S", source.load(server, "b") {}, null, null))
        library.delete(LevelKey("sonolus.sbuga.com", "a"))
        assertFalse(cache.has("a-13"))
        assertFalse(cache.has("details-a"))
        assertTrue(cache.has("engine-0"))
        assertTrue(cache.has("b-13"))
        assertNull(library.local(LevelKey("sonolus.sbuga.com", "a")))
        assertEquals("details-b", library.local(LevelKey("sonolus.sbuga.com", "b"))?.detailsKey)
    }

    @Test
    fun deleteAllRemovesEveryLevelAndUnsharedFiles() = runBlocking {
        val (library, cache, source) = setup()
        for (name in listOf("a", "b", "c")) library.add(library.entryFrom(server, "S", source.load(server, name) {}, null, null))
        library.deleteAll(setOf(LevelKey("sonolus.sbuga.com", "a"), LevelKey("sonolus.sbuga.com", "b")))
        assertEquals(listOf("c"), library.index.entries.value.map { it.name })
        assertFalse(cache.has("a-13"))
        assertFalse(cache.has("b-13"))
        assertTrue(cache.has("engine-0"))
        assertTrue(cache.has("c-13"))
    }

    @Test
    fun replacingAnEntryReleasesDroppedFiles() = runBlocking {
        val (library, cache, source) = setup()
        val loaded = source.load(server, "a") {}
        library.add(library.entryFrom(server, "S", loaded, "old-cover", null).also { cache.put("old-cover", null) { o -> o.write(1) } })
        cache.put("new-cover", null) { it.write(1) }
        library.add(library.entryFrom(server, "S", loaded, "new-cover", null))
        assertFalse(cache.has("old-cover"))
        assertTrue(cache.has("new-cover"))
    }

    @Test
    fun storageSumsUniqueLibraryKeys() = runBlocking {
        val (library, cache, source) = setup()
        library.add(library.entryFrom(server, "S", source.load(server, "a") {}, null, null))
        library.add(library.entryFrom(server, "S", source.load(server, "b") {}, null, null))
        cache.put("streamed", null) { it.write(ByteArray(1000)) }
        val info = library.storage()
        assertEquals(2, info.levels)
        assertEquals(cache.sizeOf(library.index.pinnedKeys()), info.libraryBytes)
        assertEquals(1000L, info.cacheBytes)
    }

    @Test
    fun playedItemReadsTheCachedDetails() = runBlocking {
        val (library, cache, _) = setup()
        cache.put("d", null) {
            it.write("""{"item":{"name":"lvl","title":"Title","artists":"A","rating":24,"cover":{"url":"/c.png"}}}""".toByteArray())
        }
        val item = library.playedItem(server, LoadedResources(emptyMap(), detailsKey = "d"))!!
        assertEquals("Title", item.title)
        assertEquals("A", item.artists)
        assertEquals(24.0, item.rating, 0.0)
        assertEquals(server.resolve("/c.png"), item.cover?.url)
    }

    @Test
    fun downloadedLevelUsesTheCachedCover() = runBlocking {
        val (library, cache, source) = setup()
        val loaded = source.load(server, "a") {}
        cache.put("cover-a", null) { it.write(1) }
        library.add(library.entryFrom(server, "S", loaded, "cover-a", null))
        val item = library.playedItem(server, loaded)!!
        assertEquals(cache.file("cover-a")!!.toURI().toString(), item.cover?.url)
    }

    @Test
    fun playedItemIsNullWhenDetailsUnreadable() = runBlocking {
        val (library, cache, _) = setup()
        assertNull(library.playedItem(server, LoadedResources(emptyMap(), detailsKey = "missing")))
        cache.put("bad", null) { it.write("not json".toByteArray()) }
        assertNull(library.playedItem(server, LoadedResources(emptyMap(), detailsKey = "bad")))
    }
}
