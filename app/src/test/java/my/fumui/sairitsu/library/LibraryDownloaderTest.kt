package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.gameplay.HashMismatch
import my.fumui.sairitsu.gameplay.LoadFailure
import my.fumui.sairitsu.gameplay.ResourceCache
import my.fumui.sairitsu.gameplay.ResourceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LibraryDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = ServerAddress.parse("sonolus.sbuga.com")!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var cache: ResourceCache
    private lateinit var library: Library
    private lateinit var source: FakeDownloadSource
    private lateinit var downloader: LibraryDownloader

    private fun key(name: String) = LevelKey("sonolus.sbuga.com", name)

    private fun level(name: String) = LevelItem(name = name, title = name)

    private fun setup(parallel: Int = 1) {
        cache = ResourceCache(tmp.newFolder("cache"))
        library = Library(LibraryIndex(File(tmp.root, "library.json")), cache)
        source = FakeDownloadSource(cache)
        downloader = LibraryDownloader(source, library, scope, parallel)
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun awaitState(key: LevelKey, predicate: (DownloadState?) -> Boolean) = withTimeout(5_000) {
        while (!predicate(downloader.states.value[key])) delay(5)
    }

    /** Queued levels start in no set order (each is its own coroutine), so wait for whichever runs first. */
    private suspend fun awaitAnyRunning() = withTimeout(5_000) {
        while (downloader.states.value.values.none { it is DownloadState.Running }) delay(5)
    }

    @Test
    fun downloadAddsEntryWithCoverAndPreview() = runBlocking {
        setup()
        var seenWhileLoading: DownloadState? = null
        source.beforeLoad = { seenWhileLoading = downloader.states.value[key("a")] }
        downloader.download(server, "Sbuga", level("a"))!!.join()
        assertTrue(seenWhileLoading is DownloadState.Running)
        assertNull(downloader.states.value[key("a")])
        val e = library.index.get(key("a"))!!
        assertNotNull(e.coverKey)
        assertNotNull(e.previewKey)
        assertEquals("Sbuga", e.serverTitle)
    }

    @Test
    fun downloadsRunOneAtATime() = runBlocking {
        setup()
        val gate = CompletableDeferred<Unit>()
        source.beforeLoad = { if (it == "a") gate.await() }
        val a = downloader.download(server, "S", level("a"))!!
        awaitState(key("a")) { it is DownloadState.Running }
        val b = downloader.download(server, "S", level("b"))!!
        delay(50)
        assertEquals(DownloadState.Queued, downloader.states.value[key("b")])
        gate.complete(Unit)
        a.join()
        b.join()
        assertEquals(setOf("a", "b"), library.index.entries.value.map { it.name }.toSet())
    }

    @Test
    fun downloadsRunUpToTheLimitAtOnce() = runBlocking {
        setup(parallel = 2)
        val gate = CompletableDeferred<Unit>()
        source.beforeLoad = { gate.await() }
        downloader.downloadAll(server, "S", listOf(level("a"), level("b"), level("c")))
        withTimeout(5_000) { while (source.loads.get() < 2) delay(5) }
        delay(50)
        val states = downloader.states.value
        assertEquals(2, states.values.count { it is DownloadState.Running })
        assertEquals(1, states.values.count { it == DownloadState.Queued })
        assertEquals(2, source.loads.get())
        gate.complete(Unit)
        withTimeout(5_000) { while (downloader.states.value.values.any { it !is DownloadState.Failed }) delay(5) }
        assertEquals(emptyMap<LevelKey, DownloadState>(), downloader.states.value)
        assertEquals(setOf("a", "b", "c"), library.index.entries.value.mapTo(HashSet()) { it.name })
    }

    @Test
    fun secondDownloadOfSameKeyIsIgnored() = runBlocking {
        setup()
        val gate = CompletableDeferred<Unit>()
        source.beforeLoad = { gate.await() }
        val first = downloader.download(server, "S", level("a"))!!
        assertNull(downloader.download(server, "S", level("a")))
        gate.complete(Unit)
        first.join()
        assertEquals(1, source.loads.get())
    }

    @Test
    fun cancelLeavesNoEntryAndClearsState() = runBlocking {
        setup()
        source.beforeLoad = { CompletableDeferred<Unit>().await() }
        val job = downloader.download(server, "S", level("a"))!!
        awaitState(key("a")) { it is DownloadState.Running }
        downloader.cancel(key("a"))
        job.join()
        assertNull(downloader.states.value[key("a")])
        assertTrue(library.index.entries.value.isEmpty())
    }

    @Test
    fun failureReportsKindAndAddsNothing() = runBlocking {
        setup()
        source.beforeLoad = { throw LoadFailure(ResourceKind.LEVEL_BGM, "https://x/bgm", IOException("timeout")) }
        downloader.download(server, "S", level("a"))!!.join()
        val state = downloader.states.value[key("a")] as DownloadState.Failed
        assertEquals(ResourceKind.LEVEL_BGM.wireName, state.resource)
        assertEquals("Couldn't reach the server", state.message)
        assertTrue(library.index.entries.value.isEmpty())
    }

    @Test
    fun previewFailureNamesThePreview() = runBlocking {
        setup()
        source.failSrl = "/p/" to LoadFailure(null, "https://x/p/a", IOException("timeout"))
        downloader.download(server, "S", level("a"))!!.join()
        val state = downloader.states.value[key("a")] as DownloadState.Failed
        assertEquals("level.preview", state.resource)
        assertEquals("Couldn't reach the server", state.message)
        assertTrue(library.index.entries.value.isEmpty())
    }

    @Test
    fun coverFailureNamesTheCover() = runBlocking {
        setup()
        source.failSrl = "/c/" to LoadFailure(null, "https://x/c/a", HashMismatch("k", "a", "b"))
        downloader.download(server, "S", level("a"))!!.join()
        val state = downloader.states.value[key("a")] as DownloadState.Failed
        assertEquals("level.cover", state.resource)
        assertEquals("The download was corrupted", state.message)
    }

    @Test
    fun diskFullHasStorageMessage() = runBlocking {
        setup()
        source.beforeLoad = { throw LoadFailure(null, null, IOException("write failed: ENOSPC (No space left on device)")) }
        downloader.download(server, "S", level("a"))!!.join()
        val state = downloader.states.value[key("a")] as DownloadState.Failed
        assertEquals("Not enough storage. Delete some levels and try again.", state.message)
    }

    @Test
    fun retryAfterFailureWorks() = runBlocking {
        setup()
        source.beforeLoad = { throw LoadFailure(null, null, IOException("timeout")) }
        downloader.download(server, "S", level("a"))!!.join()
        source.beforeLoad = {}
        downloader.download(server, "S", level("a"))!!.join()
        assertNotNull(library.index.get(key("a")))
        assertNull(downloader.states.value[key("a")])
    }

    @Test
    fun retriesLoadOnceWhenFilesVanishBeforeAdd() = runBlocking {
        setup()
        var first = true
        source.afterLoad = {
            if (first) {
                first = false
                cache.delete(setOf("engine-0"))
            }
        }
        downloader.download(server, "S", level("a"))!!.join()
        assertEquals(2, source.loads.get())
        assertNotNull(library.index.get(key("a")))
        assertTrue(cache.has("engine-0"))
    }

    @Test
    fun saveLoadedAddsEntry() = runBlocking {
        setup()
        val loaded = source.load(server, "a") {}
        downloader.saveLoaded(server, "S", loaded)
        assertNotNull(library.index.get(key("a"))?.coverKey)
    }

    @Test
    fun downloadAllQueuesEveryLevelOnceAndSkipsActiveOnes() = runBlocking {
        setup()
        val gate = CompletableDeferred<Unit>()
        source.beforeLoad = { gate.await() }
        downloader.download(server, "S", level("a"))
        val queued = downloader.downloadAll(server, "S", listOf(level("a"), level("b"), level("c")))
        assertEquals(2, queued)
        assertEquals(setOf(key("a"), key("b"), key("c")), downloader.states.value.keys)
        gate.complete(Unit)
        withTimeout(5_000) { while (downloader.states.value.isNotEmpty()) delay(5) }
        assertEquals(setOf("a", "b", "c"), library.index.entries.value.mapTo(HashSet()) { it.name })
    }

    @Test
    fun cancelAllCancelsRunningAndQueued() = runBlocking {
        setup()
        val gate = CompletableDeferred<Unit>()
        source.beforeLoad = { gate.await() }
        downloader.downloadAll(server, "Sbuga", listOf(level("a"), level("b"), level("c")))
        awaitAnyRunning()
        downloader.cancelAll()
        withTimeout(5_000) { while (downloader.states.value.isNotEmpty()) delay(5) }
        assertTrue(library.index.entries.value.isEmpty())
        assertEquals(1, source.loads.get()) // b and c never started
    }

    /** One state update for the whole queue: removing levels one by one copies a large map once per level. */
    @Test
    fun cancelAllClearsStatesAtOnce() = runBlocking {
        setup()
        val gate = CompletableDeferred<Unit>()
        source.beforeLoad = { gate.await() }
        downloader.downloadAll(server, "Sbuga", (1..500).map { level("l$it") })
        awaitAnyRunning()
        downloader.cancelAll()
        assertTrue(downloader.states.value.isEmpty())
        assertNull(downloader.titleOf(key("l2")))
    }

    @Test
    fun titleOfIsTheQueuedLevelsTitle() = runBlocking {
        setup()
        val gate = CompletableDeferred<Unit>()
        source.beforeLoad = { gate.await() }
        downloader.download(server, "Sbuga", LevelItem(name = "a", title = "Song A"))
        assertEquals("Song A", downloader.titleOf(key("a")))
        assertNull(downloader.titleOf(key("zzz")))
        gate.complete(Unit)
        Unit
    }
}
