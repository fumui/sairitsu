package my.fumui.sairitsu.data

import my.fumui.sairitsu.ui.FakeLevelSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CachingLevelSourceTest {
    private var clock = 0L
    private val cache = ResponseCache(ttlMillis = 1000, maxEntries = 3, now = { clock })
    private val fake = FakeLevelSource()
    private val source = CachingLevelSource(fake, cache, "a.com")

    @Test
    fun repeatsComeFromCache() = runBlocking {
        source.levelDetails("x")
        source.levelDetails("x")
        source.levelList(mapOf("b" to "2", "a" to "1"), null)
        source.levelList(mapOf("a" to "1", "b" to "2"), null)
        assertEquals(listOf("x"), fake.detailCalls)
        assertEquals(1, fake.listCalls.size)
    }

    @Test
    fun differentQueriesAndServersAreSeparate() = runBlocking {
        source.levelList(mapOf("page" to "1"), null)
        source.levelList(mapOf("page" to "2"), null)
        source.levelList(emptyMap(), "c1")
        CachingLevelSource(fake, cache, "b.com").levelList(mapOf("page" to "1"), null)
        assertEquals(4, fake.listCalls.size)
    }

    @Test
    fun invalidateDropsOnlyThatServer() {
        cache.put("a.com|info", 1)
        cache.put("a.com|levels/info", 2)
        cache.put("b.com|info", 3)
        cache.invalidate("a.com")
        assertEquals(null, cache.get("a.com|info"))
        assertEquals(null, cache.get("a.com|levels/info"))
        assertEquals(3, cache.get("b.com|info"))
    }

    @Test
    fun expiresAfterTtl() = runBlocking {
        source.levelDetails("x")
        clock = 1001
        source.levelDetails("x")
        assertEquals(listOf("x", "x"), fake.detailCalls)
    }

    @Test
    fun evictsLeastRecentlyUsed() = runBlocking {
        listOf("a", "b", "c").forEach { source.levelDetails(it) }
        source.levelDetails("a")
        source.levelDetails("d")
        source.levelDetails("a")
        source.levelDetails("b")
        assertEquals(listOf("a", "b", "c", "d", "b"), fake.detailCalls)
    }

    @Test
    fun failuresAreNotCached() = runBlocking {
        var fail = true
        fake.details = { name -> if (fail) throw IOException("down") else my.fumui.sairitsu.data.model.ItemDetails(
            item = my.fumui.sairitsu.data.model.LevelItem(name, name),
        ) }
        assertThrows(IOException::class.java) { runBlocking { source.levelDetails("x") } }
        fail = false
        assertEquals("x", source.levelDetails("x").item.name)
    }
}
