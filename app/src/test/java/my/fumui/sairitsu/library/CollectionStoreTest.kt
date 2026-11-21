package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CollectionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "collections.json")

    private fun key(name: String) = LevelKey("sonolus.sbuga.com", name)

    private val all = setOf("a", "b", "c", "gone").map(::key).toSet()

    private fun store(library: Set<LevelKey> = all) = CollectionStore(file()) { library }

    @Test
    fun createRenameDeletePersist() = runBlocking {
        val s = store()
        val a = s.create("A", setOf(key("a")))
        s.rename(a.id, "B")
        val reopened = store().collections.value.single()
        assertEquals("B", reopened.name)
        assertEquals(listOf(key("a")), reopened.levels)
        s.delete(a.id)
        assertTrue(store().collections.value.isEmpty())
    }

    @Test
    fun createTrimsTheName() = runBlocking {
        assertEquals("A", store().create("  A ").name)
    }

    @Test
    fun addAndRemoveAcrossCollections() = runBlocking {
        val s = store()
        val x = s.create("x")
        val y = s.create("y")
        s.add(setOf(x.id, y.id), setOf(key("a"), key("b")))
        assertEquals(listOf(key("a"), key("b")), s.get(x.id)!!.levels)
        assertEquals(listOf(key("a"), key("b")), s.get(y.id)!!.levels)
        s.remove(setOf(x.id), setOf(key("a")))
        assertEquals(listOf(key("b")), s.get(x.id)!!.levels)
        assertEquals(listOf(key("a"), key("b")), s.get(y.id)!!.levels)
        val reopened = store()
        assertEquals(listOf(key("b")), reopened.get(x.id)!!.levels)
        assertEquals(listOf(key("a"), key("b")), reopened.get(y.id)!!.levels)
    }

    @Test
    fun addSkipsExistingMembersAndKeepsOrder() = runBlocking {
        val s = store()
        val x = s.create("x", setOf(key("a")))
        s.add(setOf(x.id), linkedSetOf(key("b"), key("a")))
        assertEquals(listOf(key("a"), key("b")), s.get(x.id)!!.levels)
    }

    @Test
    fun removeLevelDropsFromEveryCollection() = runBlocking {
        val s = store()
        val x = s.create("x", setOf(key("a"), key("b")))
        val y = s.create("y", setOf(key("a")))
        s.removeLevel(key("a"))
        assertEquals(listOf(key("b")), s.get(x.id)!!.levels)
        assertEquals(emptyList<LevelKey>(), s.get(y.id)!!.levels)
    }

    @Test
    fun removeLevelsDropsSeveralFromEveryCollection() = runBlocking {
        val s = store()
        val x = s.create("x", setOf(key("a"), key("b"), key("c")))
        val y = s.create("y", setOf(key("a")))
        s.removeLevels(setOf(key("a"), key("b")))
        assertEquals(listOf(key("c")), s.get(x.id)!!.levels)
        assertEquals(emptyList<LevelKey>(), s.get(y.id)!!.levels)
    }

    @Test
    fun pruneOnLoadDropsMembersMissingFromLibrary() = runBlocking {
        val x = store().create("x", setOf(key("a"), key("gone")))
        assertEquals(listOf(key("a")), store(setOf(key("a"))).get(x.id)!!.levels)
        // The prune was written back, so the member doesn't come back once the library has it again.
        assertEquals(listOf(key("a")), store(setOf(key("a"), key("gone"))).get(x.id)!!.levels)
    }

    @Test
    fun corruptFileIsRenamedAndStoreStartsEmpty() {
        file().writeText("{not json")
        val s = store()
        assertTrue(s.collections.value.isEmpty())
        assertTrue(s.recoveredFromCorruption)
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("collections.json.broken-") })
        s.acknowledgeRecovery()
        assertFalse(s.recoveredFromCorruption)
    }

    @Test
    fun failedWriteLeavesStateUnchanged() = runBlocking {
        val s = store()
        val a = s.create("A")
        File(tmp.root, "collections.json.tmp").mkdir()
        try {
            s.rename(a.id, "B")
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals("A", s.get(a.id)!!.name)
    }

    // Pending memberships (browser levels that aren't downloaded yet)

    @Test
    fun pendingCommitsWhenLevelArrives() = runBlocking {
        val s = store()
        val x = s.create("x")
        val k = key("a")
        s.addPending(k, x.id)
        s.settlePending(activeDownloads = { setOf(k) }, libraryKeys = { emptySet() })
        assertEquals(mapOf(k to setOf(x.id)), s.pending.value)
        assertEquals(emptyList<LevelKey>(), s.get(x.id)!!.levels)
        s.settlePending(activeDownloads = { emptySet() }, libraryKeys = { setOf(k) })
        assertEquals(listOf(k), s.get(x.id)!!.levels)
        assertTrue(s.pending.value.isEmpty())
    }

    @Test
    fun pendingKeptOnFailure() = runBlocking {
        val s = store()
        val x = s.create("x")
        s.addPending(key("a"), x.id)
        // A failed download stays in the downloader's states until it's retried.
        s.settlePending(activeDownloads = { setOf(key("a")) }, libraryKeys = { emptySet() })
        assertEquals(setOf(x.id), s.pending.value[key("a")])
    }

    @Test
    fun pendingDroppedOnCancel() = runBlocking {
        val s = store()
        val x = s.create("x")
        s.addPending(key("a"), x.id)
        s.settlePending(activeDownloads = { emptySet() }, libraryKeys = { emptySet() })
        assertTrue(s.pending.value.isEmpty())
        assertEquals(emptyList<LevelKey>(), s.get(x.id)!!.levels)
    }

    @Test
    fun untickRemovesPending() = runBlocking {
        val s = store()
        val k = key("a")
        s.addPending(k, "x")
        s.addPending(k, "y")
        s.removePending(k, "x")
        assertEquals(mapOf(k to setOf("y")), s.pending.value)
        s.removePending(k, "y")
        assertTrue(s.pending.value.isEmpty())
    }

    @Test
    fun pendingForDeletedCollectionIsIgnored() = runBlocking {
        val s = store()
        val x = s.create("x")
        s.addPending(key("a"), x.id)
        s.delete(x.id)
        val bytes = file().readBytes()
        s.settlePending(activeDownloads = { emptySet() }, libraryKeys = { setOf(key("a")) })
        assertTrue(s.pending.value.isEmpty())
        assertTrue(bytes.contentEquals(file().readBytes()))
    }

    @Test
    fun downloadCompletingMidSettleStillCommits() = runBlocking {
        val s = store()
        val x = s.create("x")
        val k = key("a")
        s.addPending(k, x.id)
        // The downloader writes the index, then clears the level's state. Model that happening right after whichever
        // of the two reads settlePending makes first: the first read sees "before", the second "after".
        var library = emptySet<LevelKey>()
        var active = setOf(k)
        var reads = 0
        fun afterRead() {
            if (++reads == 1) {
                library = setOf(k)
                active = emptySet()
            }
        }
        s.settlePending(
            activeDownloads = { active.also { afterRead() } },
            libraryKeys = { library.also { afterRead() } },
        )
        assertEquals(listOf(k), s.get(x.id)!!.levels)
        assertTrue(s.pending.value.isEmpty())
    }

    @Test
    fun pruneWriteFailureKeepsCollections() = runBlocking {
        val x = store().create("x", setOf(key("a"), key("gone")))
        // Disk full at the next launch: the prune can't be written.
        File(tmp.root, "collections.json.tmp").mkdir()
        val s = store(setOf(key("a")))
        assertEquals(listOf(key("a")), s.get(x.id)!!.levels)
        assertFalse(s.recoveredFromCorruption)
        assertTrue(tmp.root.listFiles()!!.none { it.name.startsWith("collections.json.broken-") })
    }

    @Test
    fun noOpCallsDoNotWrite() = runBlocking {
        val s = store()
        val x = s.create("x", setOf(key("a")))
        val bytes = file().readBytes()
        file().setLastModified(1_000)
        s.add(setOf(x.id), setOf(key("a")))
        s.remove(setOf(x.id), setOf(key("b")))
        s.delete("unknown")
        s.add(setOf("unknown"), setOf(key("b")))
        s.removeLevel(key("c"))
        assertEquals(1_000, file().lastModified())
        assertTrue(bytes.contentEquals(file().readBytes()))
    }
}
