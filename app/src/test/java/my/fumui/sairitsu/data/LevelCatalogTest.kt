package my.fumui.sairitsu.data

import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.data.model.ItemInfo
import my.fumui.sairitsu.data.model.ItemList
import my.fumui.sairitsu.data.model.ItemSection
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.ServerInfo
import my.fumui.sairitsu.data.model.ServerJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LevelCatalogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = ServerAddress.parse("example.com")!!

    /** A page-numbered server over [levels] (newest first), [pageSize] a page. */
    private class PagedServer(var levels: List<LevelItem>, val pageSize: Int = 3) : LevelSource {
        val pagesFetched = mutableListOf<Int>()
        var failOnPage: Int? = null
        var newest: List<LevelItem> = emptyList()

        override suspend fun serverInfo() = ServerInfo("Example")

        override suspend fun levelInfo() = ItemInfo(
            sections = listOf(
                ItemSection("#NEWEST", "level", newest.map { ServerJson.encodeToJsonElement(LevelItem.serializer(), it) }),
            ),
        )

        override suspend fun levelList(params: Map<String, String>, cursor: String?): ItemList {
            val page = params["page"]?.toInt() ?: 0
            pagesFetched += page
            if (page == failOnPage) throw IOException("offline")
            val pageCount = maxOf(1, (levels.size + pageSize - 1) / pageSize)
            return ItemList(pageCount = pageCount, items = levels.drop(page * pageSize).take(pageSize))
        }

        override suspend fun levelDetails(name: String) = ItemDetails(LevelItem(name, name))
    }

    private fun levels(vararg names: String) = names.map { LevelItem(it, it) }

    private fun TestScope.catalog(source: LevelSource): LevelCatalog {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return LevelCatalog(CatalogFiles(tmp.root), { source }, this, now = { 1000L }, io = dispatcher)
    }

    private fun names(catalog: LevelCatalog) = catalog.state(server).value.items.map { it.name }

    @Test
    fun firstOpenDownloadsEverything() = runTest {
        val source = PagedServer(levels("a", "b", "c", "d", "e", "f", "g"))
        val catalog = catalog(source)
        catalog.open(server)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), names(catalog))
        assertTrue(catalog.state(server).value.complete)
        assertEquals(SyncStatus.Idle, catalog.state(server).value.status)
        assertEquals(7, catalog.summaries.value["example.com"]?.count)

        // A new session reads it back from disk and only checks the first page for new levels.
        val again = catalog(source)
        source.pagesFetched.clear()
        again.open(server)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), names(again))
        assertEquals(listOf(0), source.pagesFetched)
    }

    @Test
    fun interruptedDownloadResumes() = runTest {
        val source = PagedServer(levels("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"), pageSize = 1)
        source.failOnPage = 8
        val catalog = catalog(source)
        catalog.open(server)
        advanceUntilIdle()
        val failed = catalog.state(server).value
        assertTrue(failed.status is SyncStatus.Failed)
        assertTrue(!failed.complete)
        // Pages 1..6 came in the first batch and were saved; 7..9 failed together with 8.
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), failed.items.map { it.name })

        source.failOnPage = null
        source.pagesFetched.clear()
        val resumed = catalog(source)
        resumed.open(server)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"), names(resumed))
        assertTrue(resumed.state(server).value.complete)
        assertEquals(listOf(0, 7, 8, 9), source.pagesFetched)
    }

    @Test
    fun quickSyncAddsNewTopLevelsAndNewestSection() = runTest {
        val source = PagedServer(levels("c", "d", "e", "f", "g"), pageSize = 2)
        val catalog = catalog(source)
        catalog.open(server)
        advanceUntilIdle()

        // Two new songs at the top, and a new difficulty of an old song that only the Newest section shows.
        source.levels = levels("a", "b", "c", "d", "e", "f", "g", "f-append")
        source.newest = levels("f-append", "a")
        source.pagesFetched.clear()
        val next = catalog(source)
        next.open(server)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "f-append", "c", "d", "e", "f", "g"), names(next))
        assertEquals(listOf(0, 1), source.pagesFetched)

        // And it was saved.
        val third = catalog(source)
        third.open(server)
        advanceUntilIdle()
        assertEquals(8, names(third).size)
    }

    @Test
    fun refreshReplacesTheList() = runTest {
        val source = PagedServer(levels("a", "b", "c", "d"))
        val catalog = catalog(source)
        catalog.open(server)
        advanceUntilIdle()
        source.levels = levels("a", "c", "d", "x")
        catalog.refresh(server)
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "d", "x"), names(catalog))
    }

    @Test
    fun deleteForgetsTheCatalog() = runTest {
        val source = PagedServer(levels("a", "b"))
        val catalog = catalog(source)
        catalog.open(server)
        advanceUntilIdle()
        catalog.delete("example.com")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), names(catalog))
        source.pagesFetched.clear()
        val again = catalog(source)
        again.open(server)
        advanceUntilIdle()
        assertEquals(listOf(0), source.pagesFetched)
        assertEquals(listOf("a", "b"), names(again))
    }
}
