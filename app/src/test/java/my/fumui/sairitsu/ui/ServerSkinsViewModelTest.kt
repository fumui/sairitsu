package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.ItemApi
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemSummary
import my.fumui.sairitsu.gameplay.SkinLibrary
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
class ServerSkinsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var web: MockWebServer
    private lateinit var server: ServerAddress
    private val bodies = ConcurrentHashMap<String, String>()
    private val failing = ConcurrentHashMap.newKeySet<String>()

    private fun srl(path: String, body: String) =
        """{"url":"$path","hash":"${sha1Hex(body)}"}""".also { bodies[path] = body }

    private fun skin(name: String) =
        """{"name":"$name","title":"Title $name","data":${srl("/s/data-$name", "skin data $name")},
            "texture":${srl("/s/texture-$name", "skin texture $name")}}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty() in failing -> MockResponse().setResponseCode(500)
                else -> bodies[request.path!!]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
            }
        }
        web.start()
        server = ServerAddress(web.url("/"))
        bodies["/sonolus/skins/list"] = """{"pageCount":1,"items":[${skin("a")},${skin("b")}]}"""
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { web.shutdown() }
    }

    private fun vm() = ServerSkinsViewModel(
        server,
        ItemApi(server, OkHttpClient()),
        SkinLibrary(tmp.root.resolve("skins"), OkHttpClient()),
    )

    /** Waits (event-driven, on a real clock) for [rows] to satisfy [condition]. */
    private suspend fun ServerSkinsViewModel.await(condition: (List<Pair<ItemSummary, SkinRowState>>) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(10_000) { rows.first(condition) } }

    private fun List<Pair<ItemSummary, SkinRowState>>.states() = map { it.first.name to it.second }

    @Test
    fun rowsMarkSavedSkins() = runTest {
        val vm = vm()
        val loaded = vm.await { it.size == 2 }
        assertEquals(listOf("a" to SkinRowState.NotSaved, "b" to SkinRowState.NotSaved), loaded.states())

        vm.save(loaded[0].first)
        val saved = vm.await { rows -> rows.firstOrNull()?.second == SkinRowState.Saved }
        assertEquals(listOf("a" to SkinRowState.Saved, "b" to SkinRowState.NotSaved), saved.states())
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
            listOf("a" to SkinRowState.NotSaved, "b" to SkinRowState.NotSaved),
            vm.await { rows -> rows[1].second != SkinRowState.Saving }.states(),
        )
    }
}
