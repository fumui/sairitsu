package my.fumui.sairitsu.gameplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ResourceCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun putVerifiesAndStores() {
        val cache = ResourceCache(tmp.newFolder("a"))
        val body = "hello".toByteArray()
        val f = cache.put("k1", sha1Hex(body)) { it.write(body) }
        assertEquals("hello", f.readText())
        assertNotNull(cache.file("k1"))
    }

    @Test
    fun hashMismatchLeavesNothing() {
        val dir = tmp.newFolder("b")
        val cache = ResourceCache(dir)
        try {
            cache.put("k1", sha1Hex("other")) { it.write("hello".toByteArray()) }
            fail("expected HashMismatch")
        } catch (_: HashMismatch) {
        }
        assertNull(cache.file("k1"))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun interruptedWriteLeavesNoEntry() {
        val dir = tmp.newFolder("c")
        val cache = ResourceCache(dir)
        try {
            cache.put("k1", null) {
                it.write(byteArrayOf(1, 2, 3))
                throw IOException("cut")
            }
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertNull(cache.file("k1"))
        File(dir, "k2.tmp").writeText("stale")
        ResourceCache(dir)
        assertEquals(0, dir.listFiles { f -> f.name.endsWith(".tmp") }!!.size)
    }

    @Test
    fun evictionKeepsPinned() {
        val dir = tmp.newFolder("d")
        val cache = ResourceCache(dir, maxBytes = 15)
        for (k in listOf("a", "b", "c")) cache.put(k, null) { it.write(ByteArray(10)) }
        dir.resolve("a").setLastModified(1000)
        dir.resolve("b").setLastModified(2000)
        dir.resolve("c").setLastModified(3000)
        cache.evict(setOf("a"))
        // Only unpinned bytes count: b + c = 20 > 15, so the oldest unpinned (b) goes and c fits.
        assertTrue(dir.resolve("a").exists())
        assertFalse(dir.resolve("b").exists())
        assertTrue(dir.resolve("c").exists())
    }

    private fun put(cache: ResourceCache, key: String, size: Int, modified: Long, dir: File) {
        cache.put(key, null) { it.write(ByteArray(size)) }
        dir.resolve(key).setLastModified(modified)
    }

    @Test
    fun evictBudgetIgnoresPinnedFiles() {
        val dir = tmp.newFolder("g")
        val cache = ResourceCache(dir, maxBytes = 100)
        put(cache, "p1", 80, 4000, dir)
        put(cache, "p2", 80, 5000, dir)
        put(cache, "u1", 30, 1000, dir)
        put(cache, "u2", 40, 2000, dir)
        cache.evict(setOf("p1", "p2"))
        for (k in listOf("p1", "p2", "u1", "u2")) assertTrue(k, dir.resolve(k).exists())
    }

    @Test
    fun evictDeletesOldestUnpinnedOverBudget() {
        val dir = tmp.newFolder("h")
        val cache = ResourceCache(dir, maxBytes = 50)
        put(cache, "p", 500, 500, dir)
        put(cache, "a", 30, 1000, dir)
        put(cache, "b", 30, 2000, dir)
        cache.evict(setOf("p"))
        assertTrue(dir.resolve("p").exists())
        assertFalse(dir.resolve("a").exists())
        assertTrue(dir.resolve("b").exists())
    }

    @Test
    fun deleteRemovesOnlyGivenKeys() {
        val dir = tmp.newFolder("i")
        val cache = ResourceCache(dir)
        for (k in listOf("a", "b", "c")) cache.put(k, null) { it.write(ByteArray(1)) }
        cache.delete(setOf("a", "c", "missing"))
        assertFalse(dir.resolve("a").exists())
        assertTrue(dir.resolve("b").exists())
        assertFalse(dir.resolve("c").exists())
    }

    @Test
    fun sizeOfSumsExistingKeys() {
        val cache = ResourceCache(tmp.newFolder("j"))
        cache.put("a", null) { it.write(ByteArray(3)) }
        cache.put("b", null) { it.write(ByteArray(4)) }
        assertEquals(7L, cache.sizeOf(setOf("a", "b", "missing")))
    }

    @Test
    fun totalBytesSumsAll() {
        val cache = ResourceCache(tmp.newFolder("k"))
        cache.put("a", null) { it.write(ByteArray(3)) }
        cache.put("b", null) { it.write(ByteArray(4)) }
        assertEquals(7L, cache.totalBytes())
    }

    @Test
    fun pathOfDoesNoIo() {
        val dir = tmp.newFolder("m")
        val cache = ResourceCache(dir)
        put(cache, "a", 1, 1000, dir)
        assertEquals(File(dir, "a"), cache.pathOf("a"))
        assertEquals(1000L, dir.resolve("a").lastModified())
        assertEquals(File(dir, "missing"), cache.pathOf("missing"))
    }

    @Test
    fun hasDoesNotTouchLastModified() {
        val dir = tmp.newFolder("l")
        val cache = ResourceCache(dir)
        put(cache, "a", 1, 1000, dir)
        assertTrue(cache.has("a"))
        assertFalse(cache.has("missing"))
        assertEquals(1000L, dir.resolve("a").lastModified())
    }

    @Test
    fun evictionRemovesOldestFirst() {
        val dir = tmp.newFolder("e")
        val cache = ResourceCache(dir, maxBytes = 20)
        for (k in listOf("a", "b", "c")) cache.put(k, null) { it.write(ByteArray(10)) }
        dir.resolve("a").setLastModified(3000)
        dir.resolve("b").setLastModified(1000)
        dir.resolve("c").setLastModified(2000)
        cache.evict(emptySet())
        assertTrue(dir.resolve("a").exists())
        assertFalse(dir.resolve("b").exists())
        assertTrue(dir.resolve("c").exists())
    }

    @Test
    fun concurrentPutsOfSameKeyDoNotInterleave() {
        val dir = tmp.newFolder("f")
        val cache = ResourceCache(dir)
        val aWriting = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val a = Thread {
            cache.put("k", null) {
                it.write("aaaa".toByteArray())
                aWriting.countDown()
                release.await()
                it.write("AAAA".toByteArray())
            }
        }
        a.start()
        aWriting.await()
        cache.put("k", null) { it.write("bbbb".toByteArray()) }
        release.countDown()
        a.join()
        assertTrue(cache.file("k")!!.readText() in setOf("aaaaAAAA", "bbbb"))
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun keysListsEntries() {
        val dir = tmp.newFolder("keys")
        val cache = ResourceCache(dir)
        assertEquals(emptySet<String>(), cache.keys())
        cache.put("k1", null) { it.write(1) }
        cache.put("k2", null) { it.write(2) }
        dir.resolve("stale.tmp").writeText("x")
        assertEquals(setOf("k1", "k2"), cache.keys())
    }
}
