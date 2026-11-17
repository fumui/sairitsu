package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.gameplay.ResourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

fun entry(
    name: String,
    keys: List<String> = listOf("r-$name"),
    server: String = "sonolus.sbuga.com",
    addedAt: Long = 1,
    title: String = name,
    artists: String = "",
    author: String = "",
    rating: Double = 0.0,
    tags: List<String> = emptyList(),
    engineName: String? = "rush",
) = LibraryEntry(
    server = server,
    serverTitle = server,
    name = name,
    title = title,
    artists = artists,
    author = author,
    rating = rating,
    tags = tags,
    engineName = engineName,
    engineTitle = engineName,
    addedAt = addedAt,
    resourceKeys = keys.withIndex().associate { (i, k) -> ResourceKind.entries[i] to k },
    detailsKey = "d-$name",
    coverKey = null,
    previewKey = null,
)

class LibraryIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "library.json")

    @Test
    fun putThenReloadFromFile() = runBlocking {
        LibraryIndex(file()).put(entry("a"))
        val reloaded = LibraryIndex(file())
        assertEquals(listOf("a"), reloaded.entries.value.map { it.name })
        assertEquals("a", reloaded.get(LevelKey("sonolus.sbuga.com", "a"))?.name)
    }

    @Test
    fun putReplacesAndKeepsAddedAt() = runBlocking {
        val index = LibraryIndex(file())
        index.put(entry("a", addedAt = 5, title = "old"))
        index.put(entry("a", addedAt = 9, title = "new"))
        val e = index.entries.value.single()
        assertEquals("new", e.title)
        assertEquals(5L, e.addedAt)
    }

    @Test
    fun putReturnsKeysTheReplacementDropped() = runBlocking {
        val index = LibraryIndex(file())
        index.put(entry("other", keys = listOf("s")))
        index.put(entry("a", keys = listOf("a", "b", "s")))
        val released = index.put(entry("a", keys = listOf("a", "c", "s")))
        assertEquals(setOf("b"), released)
    }

    @Test
    fun removeKeepsSharedKeys() = runBlocking {
        val index = LibraryIndex(file())
        index.put(entry("x", keys = listOf("e", "x")))
        index.put(entry("y", keys = listOf("e", "y")))
        val released = index.remove(LevelKey("sonolus.sbuga.com", "x"))
        assertEquals(setOf("x", "d-x"), released)
        assertEquals(setOf("e", "y", "d-y"), index.pinnedKeys())
    }

    @Test
    fun removeAbsentReturnsEmpty() = runBlocking {
        assertEquals(emptySet<String>(), LibraryIndex(file()).remove(LevelKey("s", "none")))
    }

    @Test
    fun corruptFileIsRenamedAndIndexStartsEmpty() {
        file().writeText("{not json")
        val index = LibraryIndex(file())
        assertTrue(index.entries.value.isEmpty())
        assertTrue(index.recoveredFromCorruption)
        val broken = tmp.root.listFiles()!!.filter { Regex("library\\.json\\.broken-\\d+").matches(it.name) }
        assertEquals(1, broken.size)
        assertFalse(file().exists())
    }

    @Test
    fun missingFileIsAnEmptyIndex() {
        val index = LibraryIndex(file())
        assertTrue(index.entries.value.isEmpty())
        assertFalse(index.recoveredFromCorruption)
        assertNull(index.get(LevelKey("s", "a")))
    }

    @Test
    fun leftoverTempFileIsIgnored() = runBlocking {
        LibraryIndex(file()).put(entry("a"))
        File(tmp.root, "library.json.tmp").writeText("garbage")
        val index = LibraryIndex(file())
        assertEquals(listOf("a"), index.entries.value.map { it.name })
        assertFalse(index.recoveredFromCorruption)
    }

    @Test
    fun entrySerializesResourceKindMapByName() = runBlocking {
        LibraryIndex(file()).put(entry("a", keys = List(15) { "k$it" }))
        assertTrue(file().readText().contains("\"LEVEL_BGM\""))
    }
}
