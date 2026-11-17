package my.fumui.sairitsu.library

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import my.fumui.sairitsu.MemoryDataStore
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.gameplay.ResourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryQueryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun roundTrip() = runBlocking {
        val store = LibraryQueryStore(MemoryDataStore())
        assertEquals(LibraryQuery(), store.query.first())
        val q = LibraryQuery(text = "miku", tags = setOf("Expert"), sort = LibrarySort.Rating, descending = false)
        store.save(q)
        assertEquals(q, store.query.first())
    }

    @Test
    fun oldSavedQueryWithoutCollectionsDecodes() = runBlocking {
        val mem = MemoryDataStore(mutablePreferencesOf(stringPreferencesKey("library_query") to """{"text":"a"}"""))
        val query = LibraryQueryStore(mem).query.first()
        assertEquals("a", query.text)
        assertEquals(emptySet<String>(), query.collections)
    }

    @Test
    fun unreadableValueGivesDefault() = runBlocking {
        val mem = MemoryDataStore(mutablePreferencesOf(stringPreferencesKey("library_query") to "{bad"))
        assertEquals(LibraryQuery(), LibraryQueryStore(mem).query.first())
    }

    @Test
    fun toLevelItemUsesFileUris() {
        val dir = tmp.newFolder("cache")
        val files = mapOf("cover" to File(dir, "cover"), "bgm" to File(dir, "bgm")).onEach { it.value.writeText("x") }
        val e = entry("a", title = "T", artists = "A", author = "B", rating = 24.0, tags = listOf("Expert")).copy(
            coverKey = "cover",
            previewKey = null,
            resourceKeys = mapOf(ResourceKind.LEVEL_BGM to "bgm"),
        )
        val item = e.toLevelItem { files[it] }
        assertEquals("a", item.name)
        assertEquals("T", item.title)
        assertEquals(24.0, item.rating, 0.0)
        assertEquals(listOf("Expert"), item.tags.map { it.title })
        assertEquals("rush", item.engine?.name)
        val cover = item.cover!!.url!!
        assertTrue(cover.startsWith("file:"))
        assertEquals(cover, ServerAddress.parse("sonolus.sbuga.com")!!.resolve(cover))
        assertTrue(item.bgm!!.url!!.startsWith("file:"))
        assertNull(item.preview)
    }
}
