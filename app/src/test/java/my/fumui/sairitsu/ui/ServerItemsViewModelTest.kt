package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.ItemApi
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemSummary
import my.fumui.sairitsu.gameplay.ItemKind
import my.fumui.sairitsu.gameplay.ItemLibrary
import my.fumui.sairitsu.gameplay.ItemPreview
import kotlinx.coroutines.runBlocking
import my.fumui.sairitsu.gameplay.sha1Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class ServerItemsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var web: MockWebServer
    private lateinit var server: ServerAddress
    private val bodies = ConcurrentHashMap<String, String>()
    private val failing = ConcurrentHashMap.newKeySet<String>()
    private val held = ConcurrentHashMap.newKeySet<String>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val release = java.util.concurrent.CountDownLatch(1)

    private fun srl(path: String, body: String) =
        """{"url":"$path","hash":"${sha1Hex(body)}"}""".also { bodies[path] = body }

    private fun skin(name: String) =
        """{"name":"$name","title":"Title $name","data":${srl("/s/data-$name", "skin data $name")},
            "texture":${srl("/s/texture-$name", "skin texture $name")}}"""

    private fun background(name: String) =
        """{"name":"$name","title":"Title $name","image":${srl("/b/image-$name", "bg image $name")},
            "data":${srl("/b/data-$name", "bg data $name")},
            "configuration":${srl("/b/config-$name", "bg config $name")}}"""

    private fun effect(name: String) =
        """{"name":"$name","title":"Title $name","data":${srl("/e/data-$name", "fx data $name")},
            "audio":${srl("/e/audio-$name", "fx audio $name")}}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty() in held -> {
                    requested += request.path.orEmpty()
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    MockResponse().setResponseCode(500)
                }
                request.path.orEmpty() in failing -> MockResponse().setResponseCode(500)
                else -> bodies[request.path!!]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
            }
        }
        web.start()
        server = ServerAddress(web.url("/"))
        bodies["/sonolus/skins/list"] = """{"pageCount":1,"items":[${skin("a")},${skin("b")}]}"""
        bodies["/sonolus/backgrounds/list"] = """{"pageCount":1,"items":[${background("a")}]}"""
        bodies["/sonolus/effects/list"] = """{"pageCount":1,"items":[${effect("a")}]}"""
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { web.shutdown() }
    }

    private fun library() = ItemLibrary(tmp.root.resolve("items"), OkHttpClient())

    private fun vm(kind: ItemKind = ItemKind.Skin, library: ItemLibrary = library()) =
        ServerItemsViewModel(server, kind, ItemApi(server, OkHttpClient()), library)

    /** Waits (event-driven, on a real clock) for [rows] to satisfy [condition]. */
    private suspend fun ServerItemsViewModel.await(condition: (List<Pair<ItemSummary, ItemRowState>>) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(10_000) { rows.first(condition) } }

    private fun List<Pair<ItemSummary, ItemRowState>>.states() = map { it.first.name to it.second }

    @Test
    fun rowsMarkSavedSkins() = runTest {
        val vm = vm()
        val loaded = vm.await { it.size == 2 }
        assertEquals(listOf("a" to ItemRowState.NotSaved, "b" to ItemRowState.NotSaved), loaded.states())

        vm.save(loaded[0].first)
        val saved = vm.await { rows -> rows.firstOrNull()?.second == ItemRowState.Saved }
        assertEquals(listOf("a" to ItemRowState.Saved, "b" to ItemRowState.NotSaved), saved.states())
    }

    @Test
    fun backgroundRowsMarkSaved() = runTest {
        val library = library()
        val vm = vm(ItemKind.Background, library)
        val loaded = vm.await { it.size == 1 }
        assertEquals(listOf("a" to ItemRowState.NotSaved), loaded.states())

        vm.save(loaded[0].first)
        val saved = vm.await { rows -> rows.firstOrNull()?.second == ItemRowState.Saved }
        assertEquals(listOf("a" to ItemRowState.Saved), saved.states())
        assertEquals(
            listOf(ItemLibrary.idOf(ItemKind.Background, server.linkAddress, "a")),
            library.items.value.map { it.id },
        )
    }

    @Test
    fun sfxSaveDownloadsEffectFiles() = runTest {
        val library = library()
        val vm = vm(ItemKind.Sfx, library)
        val loaded = vm.await { it.size == 1 }

        vm.save(loaded[0].first)
        vm.await { rows -> rows.firstOrNull()?.second == ItemRowState.Saved }

        val saved = library.items.value.single()
        assertEquals(ItemKind.Sfx, saved.kind)
        assertEquals(setOf("effect.data", "effect.audio"), saved.files.keys)
    }

    private fun previewFor(vm: ServerItemsViewModel, item: ItemSummary): ItemPreview? {
        var ready: ItemPreview? = null
        vm.preview(item) { ready = it }
        return runBlocking { withTimeout(10_000) { vm.previewing.first { item.name !in it } }; ready }
    }

    @Test
    fun previewDownloadsWithoutSaving() = runTest {
        val library = library()
        val vm = vm(ItemKind.Skin, library)
        val loaded = vm.await { it.size == 2 }

        val ready = previewFor(vm, loaded[0].first)

        assertEquals(ItemPreview(ItemKind.Skin, ItemLibrary.idOf(ItemKind.Skin, server.linkAddress, "a")), ready)
        assertEquals(listOf("a" to ItemRowState.NotSaved, "b" to ItemRowState.NotSaved), vm.rows.value.states())
        assertEquals(emptyList<Any>(), library.items.value)
    }

    @Test
    fun newerPreviewCancelsOlder() = runTest {
        val vm = vm(ItemKind.Skin)
        val loaded = vm.await { it.size == 2 }
        held += "/s/data-a"
        val ready = java.util.concurrent.CopyOnWriteArrayList<ItemPreview>()
        try {
            vm.preview(loaded[0].first) { ready += it }
            withContext(Dispatchers.Default) { withTimeout(10_000) { while (!requested.contains("/s/data-a")) yield() } }
            vm.preview(loaded[1].first) { ready += it }
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.previewing.first { it.isEmpty() } } }
        } finally {
            release.countDown()
        }
        assertEquals(listOf(ItemPreview(ItemKind.Skin, ItemLibrary.idOf(ItemKind.Skin, server.linkAddress, "b"))), ready.toList())
    }

    @Test
    fun saveAfterPreviewPromotes() = runTest {
        val library = library()
        val vm = vm(ItemKind.Skin, library)
        val loaded = vm.await { it.size == 2 }
        previewFor(vm, loaded[0].first)
        val requests = web.requestCount

        vm.save(loaded[0].first)
        val saved = vm.await { rows -> rows.firstOrNull()?.second == ItemRowState.Saved }

        assertEquals(listOf("a" to ItemRowState.Saved, "b" to ItemRowState.NotSaved), saved.states())
        assertEquals(requests, web.requestCount)
    }

    @Test
    fun previewFailureReportsError() = runTest {
        val vm = vm()
        val loaded = vm.await { it.size == 2 }
        failing += "/s/texture-a"
        val error = async(start = CoroutineStart.UNDISPATCHED) { vm.errors.first() }
        var called = false

        vm.preview(loaded[0].first) { called = true }
        val message = withContext(Dispatchers.Default) { withTimeout(10_000) { error.await() } }

        assertEquals("The server returned an error (500)", message)
        assertEquals(emptySet<String>(), vm.previewing.value)
        assertEquals(false, called)
    }

    @Test
    fun saveFailureEmitsError() = runTest {
        val vm = vm()
        val loaded = vm.await { it.size == 2 }
        failing += "/s/texture-b"
        val error = async(start = CoroutineStart.UNDISPATCHED) { vm.errors.first() }

        vm.save(loaded[1].first)
        val message = withContext(Dispatchers.Default) { withTimeout(10_000) { error.await() } }

        assertEquals("The server returned an error (500)", message)
        assertEquals(
            listOf("a" to ItemRowState.NotSaved, "b" to ItemRowState.NotSaved),
            vm.await { rows -> rows[1].second != ItemRowState.Saving }.states(),
        )
    }
}
