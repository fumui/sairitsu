package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LevelLoaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var web: MockWebServer
    private lateinit var server: ServerAddress
    private val bodies = ConcurrentHashMap<String, MutableList<String>>()
    private val hits = ConcurrentHashMap<String, Int>()

    /** One distinct body per resource kind, served at /r/<wireName>. */
    private fun bodyOf(kind: ResourceKind) = "body of ${kind.wireName}"

    private fun srl(kind: ResourceKind, hash: String? = sha1Hex(bodyOf(kind))): String {
        val h = if (hash == null) "" else ""","hash":"$hash""""
        return """{"url":"/r/${kind.wireName}"$h}"""
    }

    private fun details(nullHash: ResourceKind? = null): String {
        fun s(k: ResourceKind) = srl(k, if (k == nullHash) null else sha1Hex(bodyOf(k)))
        return """
            {"item":{"name":"lvl",
              "data":${s(ResourceKind.LEVEL_DATA)},"bgm":${s(ResourceKind.LEVEL_BGM)},
              "useSkin":{"useDefault":true},"useBackground":{"useDefault":true},
              "useEffect":{"useDefault":true},"useParticle":{"useDefault":true},
              "engine":{"name":"e",
                "watchData":${s(ResourceKind.ENGINE_WATCH_DATA)},"playData":${s(ResourceKind.ENGINE_PLAY_DATA)},
                "configuration":${s(ResourceKind.ENGINE_CONFIGURATION)},"rom":${s(ResourceKind.ENGINE_ROM)},
                "skin":{"data":${s(ResourceKind.SKIN_DATA)},"texture":${s(ResourceKind.SKIN_TEXTURE)}},
                "effect":{"data":${s(ResourceKind.EFFECT_DATA)},"audio":${s(ResourceKind.EFFECT_AUDIO)}},
                "particle":{"data":${s(ResourceKind.PARTICLE_DATA)},"texture":${s(ResourceKind.PARTICLE_TEXTURE)}},
                "background":{"image":${s(ResourceKind.BACKGROUND_IMAGE)},"data":${s(ResourceKind.BACKGROUND_DATA)},
                  "configuration":${s(ResourceKind.BACKGROUND_CONFIGURATION)}}}}}
        """.trimIndent()
    }

    @Before
    fun setUp() {
        web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                hits.merge(path, 1, Int::plus)
                val queue = bodies[path] ?: return MockResponse().setResponseCode(404)
                val body = synchronized(queue) { if (queue.size > 1) queue.removeAt(0) else queue[0] }
                return MockResponse().setBody(body)
            }
        }
        web.start()
        server = ServerAddress(web.url("/"))
        bodies["/sonolus/levels/lvl"] = mutableListOf(details())
        for (k in ResourceKind.entries) bodies["/r/${k.wireName}"] = mutableListOf(bodyOf(k))
    }

    @After
    fun tearDown() {
        runCatching { web.shutdown() }
    }

    private fun loader(cacheDir: String = "cache") =
        LevelLoader(OkHttpClient(), ResourceCache(tmp.root.resolve(cacheDir)))

    /** A client that fails every request, counting them. */
    private val offlineCalls = java.util.concurrent.atomic.AtomicInteger()
    private val offlineClient = OkHttpClient.Builder().addInterceptor {
        offlineCalls.incrementAndGet()
        throw java.io.IOException("offline")
    }.build()

    @Test
    fun libraryLevelLoadsWithZeroRequests() = runTest {
        val first = loader().load(server, "lvl") {}
        val local = LocalLevel(first.detailsKey, first.keys)
        val offline = LevelLoader(offlineClient, ResourceCache(tmp.root.resolve("cache")), localLevel = { local })
        val result = offline.load(server, "lvl") {}
        assertEquals(0, offlineCalls.get())
        assertEquals(first.files, result.files)
        assertEquals(first.keys, result.keys)
        assertEquals(first.detailsKey, result.detailsKey)
    }

    @Test
    fun engineWithoutRomLoadsOnlineAndOffline() = runTest {
        bodies["/sonolus/levels/lvl"] = mutableListOf(details().replace(""""rom":${srl(ResourceKind.ENGINE_ROM)},""", ""))
        val first = loader().load(server, "lvl") {}
        assertEquals(ResourceKind.entries.toSet() - ResourceKind.ENGINE_ROM, first.files.keys)
        assertEquals(0, hits["/r/engine.rom"] ?: 0)
        val local = LocalLevel(first.detailsKey, first.keys)
        val offline = LevelLoader(offlineClient, ResourceCache(tmp.root.resolve("cache")), localLevel = { local })
        assertEquals(first.files, offline.load(server, "lvl") {}.files)
        assertEquals(0, offlineCalls.get())
    }

    @Test
    fun libraryLookupUsesLinkAddress() = runTest {
        val seen = mutableListOf<my.fumui.sairitsu.data.LevelKey>()
        val parsed = ServerAddress.parse("http://127.0.0.1:${web.port}/")!!
        LevelLoader(OkHttpClient(), ResourceCache(tmp.root.resolve("cache")), localLevel = { seen += it; null })
            .load(parsed, "lvl") {}
        assertEquals(listOf(my.fumui.sairitsu.data.LevelKey("127.0.0.1:${web.port}", "lvl")), seen)
    }

    @Test
    fun missingLibraryFileFallsBackToNetwork() = runTest {
        val first = loader().load(server, "lvl") {}
        first.files.getValue(ResourceKind.SKIN_DATA).delete()
        val local = LocalLevel(first.detailsKey, first.keys)
        val result = LevelLoader(OkHttpClient(), ResourceCache(tmp.root.resolve("cache")), localLevel = { local })
            .load(server, "lvl") {}
        assertEquals(bodyOf(ResourceKind.SKIN_DATA), result.files.getValue(ResourceKind.SKIN_DATA).readText())
    }

    @Test
    fun missingLibraryFileOfflineIsDamaged() = runTest {
        val first = loader().load(server, "lvl") {}
        first.files.getValue(ResourceKind.SKIN_DATA).delete()
        val local = LocalLevel(first.detailsKey, first.keys)
        try {
            LevelLoader(offlineClient, ResourceCache(tmp.root.resolve("cache")), localLevel = { local }).load(server, "lvl") {}
            fail("expected LoadFailure")
        } catch (e: LoadFailure) {
            assertTrue(e.damaged)
        }
    }

    @Test
    fun engineConfigurationDownloadsOnlyThatFile() = runTest {
        val file = loader().engineConfiguration(server, "lvl")
        assertEquals(bodyOf(ResourceKind.ENGINE_CONFIGURATION), file.readText())
        assertEquals(setOf("/sonolus/levels/lvl", "/r/engine.configuration"), hits.keys)
    }

    @Test
    fun engineConfigurationOfLibraryLevelNeedsNoNetwork() = runTest {
        val first = loader().load(server, "lvl") {}
        val local = LocalLevel(first.detailsKey, first.keys)
        val offline = LevelLoader(offlineClient, ResourceCache(tmp.root.resolve("cache")), localLevel = { local })
        val file = offline.engineConfiguration(server, "lvl")
        assertEquals(0, offlineCalls.get())
        assertEquals(first.files.getValue(ResourceKind.ENGINE_CONFIGURATION), file)
    }

    @Test
    fun engineConfigurationIsReusedFromTheCache() = runTest {
        loader().engineConfiguration(server, "lvl")
        web.shutdown()
        assertEquals(bodyOf(ResourceKind.ENGINE_CONFIGURATION), loader().engineConfiguration(server, "lvl").readText())
    }

    @Test
    fun evictPinsLibraryKeys() = runTest {
        val cache = ResourceCache(tmp.root.resolve("cache"), maxBytes = 1)
        cache.put("k", null) { it.write(ByteArray(100)) }
        LevelLoader(OkHttpClient(), cache, pinnedKeys = { setOf("k") }).load(server, "lvl") {}
        assertTrue(cache.has("k"))
    }

    @Test
    fun withoutLibraryPinsOtherFilesAreEvicted() = runTest {
        val cache = ResourceCache(tmp.root.resolve("cache"), maxBytes = 1)
        cache.put("k", null) { it.write(ByteArray(100)) }
        LevelLoader(OkHttpClient(), cache).load(server, "lvl") {}
        assertTrue(!cache.has("k"))
    }

    @Test
    fun fetchSrlCachesByHashAndReturnsKey() = runTest {
        bodies["/cover.png"] = mutableListOf("cover bytes")
        val cache = ResourceCache(tmp.root.resolve("cache"))
        val key = LevelLoader(OkHttpClient(), cache)
            .fetchSrl(server, my.fumui.sairitsu.data.model.Srl("/cover.png", sha1Hex("cover bytes")))
        assertEquals(sha1Hex("cover bytes"), key)
        assertEquals("cover bytes", cache.file(key!!)!!.readText())
    }

    @Test
    fun fetchSrlWithStaleServerHashKeepsTheFileUnderItsUrl() = runTest {
        bodies["/preview.mp3"] = mutableListOf("new preview")
        val cache = ResourceCache(tmp.root.resolve("cache"))
        val srl = my.fumui.sairitsu.data.model.Srl("/preview.mp3", sha1Hex("old preview"))
        val key = LevelLoader(OkHttpClient(), cache).fetchSrl(server, srl)!!
        assertTrue(key.startsWith("url-"))
        assertEquals("new preview", cache.file(key)!!.readText())
        assertNull(cache.file(sha1Hex("old preview")))
        val downloads = hits.getValue("/preview.mp3")
        assertEquals(key, LevelLoader(OkHttpClient(), cache).fetchSrl(server, srl))
        assertEquals(downloads, hits.getValue("/preview.mp3"))
    }

    @Test
    fun fetchSrlNullUrlReturnsNull() = runTest {
        val l = LevelLoader(offlineClient, ResourceCache(tmp.root.resolve("cache")))
        assertNull(l.fetchSrl(server, null))
        assertNull(l.fetchSrl(server, my.fumui.sairitsu.data.model.Srl(null, null)))
        assertEquals(0, offlineCalls.get())
    }

    @Test
    fun downloadsAndCachesAll() = runTest {
        val progress = mutableListOf<LoadProgress>()
        val result = loader().load(server, "lvl") { progress += it }
        assertEquals(ResourceKind.entries.toSet(), result.files.keys)
        for ((k, f) in result.files) assertEquals(bodyOf(k), f.readText())
        assertEquals(15, progress.last().done)
        assertEquals(15, progress.last().total)
        assertTrue(progress.any { it.kind == ResourceKind.LEVEL_BGM && it.bytes > 0 })
    }

    @Test
    fun secondLoadNeedsNoNetwork() = runTest {
        loader().load(server, "lvl") {}
        web.shutdown()
        val result = loader().load(server, "lvl") {}
        assertEquals(15, result.files.size)
        assertEquals(bodyOf(ResourceKind.LEVEL_DATA), result.files.getValue(ResourceKind.LEVEL_DATA).readText())
    }

    @Test
    fun nullHashIsKeyedByUrlAndNotVerified() = runTest {
        bodies["/sonolus/levels/lvl"] = mutableListOf(details(nullHash = ResourceKind.SKIN_DATA))
        val l = loader()
        val first = l.load(server, "lvl") {}
        assertTrue(first.files.getValue(ResourceKind.SKIN_DATA).name.startsWith("url-"))
        web.shutdown()
        val second = l.load(server, "lvl") {}
        assertEquals(bodyOf(ResourceKind.SKIN_DATA), second.files.getValue(ResourceKind.SKIN_DATA).readText())
    }

    @Test
    fun hashMismatchNotCachedAndRetriedOnce() = runTest {
        val path = "/r/${ResourceKind.EFFECT_AUDIO.wireName}"
        bodies[path] = mutableListOf("corrupt", bodyOf(ResourceKind.EFFECT_AUDIO))
        val result = loader().load(server, "lvl") {}
        assertEquals(bodyOf(ResourceKind.EFFECT_AUDIO), result.files.getValue(ResourceKind.EFFECT_AUDIO).readText())
        assertEquals(2, hits[path])

        bodies[path] = mutableListOf("corrupt")
        hits.clear()
        val cache = ResourceCache(tmp.root.resolve("cache2"))
        try {
            LevelLoader(OkHttpClient(), cache).load(server, "lvl") {}
            fail("expected LoadFailure")
        } catch (e: LoadFailure) {
            assertEquals(ResourceKind.EFFECT_AUDIO, e.kind)
            assertTrue(e.url!!.endsWith(path))
            assertTrue(e.cause is HashMismatch)
        }
        assertEquals(2, hits[path])
        assertNull(cache.file(sha1Hex(bodyOf(ResourceKind.EFFECT_AUDIO))))
    }

    @Test
    fun cachedDetailsUsedWhenNetworkFails() = runTest {
        loader().load(server, "lvl") {}
        web.shutdown()
        val result = loader().load(server, "lvl") {}
        assertEquals(15, result.files.size)
    }

    @Test
    fun noDetailsAndNoCacheFails() = runTest {
        web.shutdown()
        try {
            loader().load(server, "lvl") {}
            fail("expected LoadFailure")
        } catch (e: LoadFailure) {
            assertNull(e.kind)
        }
    }

    @Test
    fun badDetailsBodyDoesNotOverwriteCachedCopy() = runTest {
        loader().load(server, "lvl") {}
        bodies["/sonolus/levels/lvl"] = mutableListOf("<html>portal</html>")
        assertEquals(15, loader().load(server, "lvl") {}.files.size)
        bodies["/sonolus/levels/lvl"] = mutableListOf("""{"item":{"name":"x"}}""")
        assertEquals(15, loader().load(server, "lvl") {}.files.size)
        web.shutdown()
        assertEquals(15, loader().load(server, "lvl") {}.files.size)
    }

    @Test
    fun httpErrorOnDetailsFallsBackToCache() = runTest {
        loader().load(server, "lvl") {}
        bodies.remove("/sonolus/levels/lvl")
        assertEquals(15, loader().load(server, "lvl") {}.files.size)
    }

    @Test
    fun badDetailsWithoutCacheFails() = runTest {
        bodies["/sonolus/levels/lvl"] = mutableListOf("<html>portal</html>")
        try {
            loader().load(server, "lvl") {}
            fail("expected LoadFailure")
        } catch (e: LoadFailure) {
            assertNull(e.kind)
        }
    }

    @Test
    fun malformedUrlBecomesLoadFailure() = runTest {
        bodies["/sonolus/levels/lvl"] =
            mutableListOf(details().replace("\"/r/skin.data\"", "\"http://[bad\""))
        try {
            loader().load(server, "lvl") {}
            fail("expected LoadFailure")
        } catch (e: LoadFailure) {
            assertEquals(ResourceKind.SKIN_DATA, e.kind)
            assertEquals("http://[bad", e.url)
        }
    }

    @Test
    fun progressCallbackFailureBecomesLoadFailure() = runTest {
        try {
            loader().load(server, "lvl") { if (it.bytes > 0) throw IllegalStateException("boom") }
            fail("expected LoadFailure")
        } catch (e: LoadFailure) {
            assertTrue(e.cause is IllegalStateException)
        }
    }

    @Test
    fun cancelMidDownloadStopsAndLeavesNothing() = runBlocking {
        val slow = ResourceKind.ENGINE_ROM
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                hits.merge(path, 1, Int::plus)
                if (path == "/r/${slow.wireName}") {
                    return MockResponse().setBody("x".repeat(1000)).throttleBody(10, 1, TimeUnit.SECONDS)
                }
                val body = bodies[path]?.get(0) ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(body)
            }
        }
        val dir = tmp.root.resolve("cancel")
        val started = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            LevelLoader(OkHttpClient(), ResourceCache(dir)).load(server, "lvl") {
                if (it.kind == slow && it.bytes > 0) started.countDown()
            }
        }
        assertTrue(withContext(Dispatchers.IO) { started.await(20, TimeUnit.SECONDS) })
        withTimeout(10_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertNull(hits["/r/${ResourceKind.LEVEL_BGM.wireName}"])
        assertEquals(0, dir.listFiles { f -> f.name.endsWith(".tmp") }!!.size)
        assertNull(ResourceCache(dir).file(sha1Hex(bodyOf(slow))))
    }
}
