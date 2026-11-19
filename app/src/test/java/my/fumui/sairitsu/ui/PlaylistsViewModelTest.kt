package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.ItemApi
import my.fumui.sairitsu.data.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModelTest {
    private lateinit var web: MockWebServer
    private val p2Calls = AtomicInteger()

    private fun level(name: String) = """{"name":"$name","title":"Level $name"}"""

    private fun playlist(name: String, vararg levels: String) =
        """{"name":"$name","title":"Pack $name","levels":[${levels.joinToString(",") { level(it) }}]}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/sonolus/playlists/list" ->
                    MockResponse().setBody("""{"pageCount":1,"items":[{"title":"no name"},${playlist("p1", "a")},${playlist("p2")}]}""")
                "/sonolus/playlists/p1" -> MockResponse().setBody("""{"item":${playlist("p1", "a", "b", "c")}}""")
                "/sonolus/playlists/slow" ->
                    MockResponse().setBody("""{"item":${playlist("slow", "s")}}""").setBodyDelay(600, TimeUnit.MILLISECONDS)
                "/sonolus/playlists/dup" -> MockResponse().setBody("""{"item":${playlist("dup", "a", "b", "a")}}""")
                "/sonolus/playlists/p2" ->
                    if (p2Calls.getAndIncrement() == 0) MockResponse().setResponseCode(500)
                    else MockResponse().setBody("""{"item":${playlist("p2", "x")}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        web.start()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { web.shutdown() }
    }

    private fun api() = ItemApi(ServerAddress(web.url("/")), OkHttpClient())

    private suspend fun <T> await(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    @Test
    fun playlistsDecodeFromPager() = runTest {
        val vm = PlaylistsViewModel(api())
        val playlists = await { vm.playlists.first { it.isNotEmpty() } }
        assertEquals(listOf("p1", "p2"), playlists.map { it.name })
    }

    @Test
    fun detailsReturnsFullLevels() = runTest {
        val vm = PlaylistsViewModel(api())
        val details = await { vm.details("p1") }
        assertEquals(listOf("a", "b", "c"), details.getOrThrow().levels.map { it.name })
    }

    @Test
    fun detailsFailureIsResultFailure() = runTest {
        val vm = PlaylistsViewModel(api())
        assertTrue(await { vm.details("p2") }.isFailure)
    }

    @Test
    fun detailsDropDuplicateLevels() = runTest {
        val vm = PlaylistsViewModel(api())
        assertEquals(listOf("a", "b"), await { vm.details("dup") }.getOrThrow().levels.map { it.name })
    }

    @Test
    fun requestSaveShowsLoadingThenReady() = runTest {
        val vm = PlaylistsViewModel(api())
        vm.requestSave("p1")
        assertEquals("p1", vm.saveRequest.value?.name)
        val ready = await { vm.saveRequest.first { it?.playlist is Loadable.Ready } }!!
        assertEquals(3, (ready.playlist as Loadable.Ready).value.levels.size)
        vm.dismissSave()
        assertNull(vm.saveRequest.value)
    }

    @Test
    fun newerRequestWins() = runTest {
        val vm = PlaylistsViewModel(api())
        vm.requestSave("slow")
        vm.requestSave("p1")
        await { vm.saveRequest.first { it?.playlist is Loadable.Ready } }
        // Outlast the slow reply: it must not replace the newer request.
        withContext(Dispatchers.Default) { delay(900) }
        assertEquals("p1", vm.saveRequest.value?.name)
        assertEquals(3, (vm.saveRequest.value?.playlist as Loadable.Ready).value.levels.size)
    }

    @Test
    fun requestFailureIsError() = runTest {
        val vm = PlaylistsViewModel(api())
        vm.requestSave("p2")
        val failed = await { vm.saveRequest.first { it?.playlist is Loadable.Error } }
        assertEquals("p2", failed?.name)
    }

    @Test
    fun playlistViewModelLoadsAndRetries() = runTest {
        val vm = PlaylistViewModel(api(), "p2")
        await { vm.playlist.first { it is Loadable.Error } }
        vm.retry()
        val ready = await { vm.playlist.first { it is Loadable.Ready } } as Loadable.Ready
        assertEquals(listOf("x"), ready.value.levels.map { it.name })
    }
}
