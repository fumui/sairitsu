package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.ItemApi
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemSummary
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PostsViewModelTest {
    private lateinit var web: MockWebServer
    private val p2Calls = AtomicInteger()

    private fun summary(name: String) = """{"name":"$name","title":"Post $name","author":"Ann","time":1000}"""

    private fun details(name: String, body: String) = """{"item":${summary(name)},"description":"$body"}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/sonolus/posts/list" ->
                    MockResponse().setBody("""{"pageCount":1,"items":[{"junk":1},${summary("p1")},${summary("p2")}]}""")
                "/sonolus/posts/p1" ->
                    MockResponse().setBody(details("p1", "Body 1")).setBodyDelay(600, TimeUnit.MILLISECONDS)
                "/sonolus/posts/p2" ->
                    if (p2Calls.getAndIncrement() == 0) MockResponse().setResponseCode(500)
                    else MockResponse().setBody(details("p2", "Body 2"))
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

    private fun vm() = PostsViewModel(ItemApi(ServerAddress(web.url("/")), OkHttpClient()))

    /** Waits (event-driven, on a real clock) for [PostsViewModel.details] to satisfy [condition]. */
    private suspend fun PostsViewModel.await(condition: (Loadable<PostView>) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(10_000) { details.first(condition) } }

    @Test
    fun postsDecodeFromPager() = runTest {
        val vm = vm()
        val posts = withContext(Dispatchers.Default) { withTimeout(10_000) { vm.posts.first { it.isNotEmpty() } } }
        assertEquals(listOf("p1", "p2"), posts.map { it.name })
    }

    @Test
    fun selectLoadsDetails() = runTest {
        val vm = vm()
        assertEquals(Loadable.Idle, vm.details.value)
        vm.posts.let { withContext(Dispatchers.Default) { withTimeout(10_000) { it.first { p -> p.isNotEmpty() } } } }
        vm.select("p1")
        val ready = vm.await { it is Loadable.Ready }
        assertEquals(
            Loadable.Ready(PostView(ItemSummary("p1", "Post p1", author = "Ann", time = 1000), "Body 1")),
            ready,
        )
        assertEquals("p1", vm.selected.value)
    }

    @Test
    fun latestSelectionWins() = runTest {
        val vm = vm()
        vm.posts.let { withContext(Dispatchers.Default) { withTimeout(10_000) { it.first { p -> p.isNotEmpty() } } } }
        vm.select("p1")
        p2Calls.set(1)
        vm.select("p2")
        val ready = vm.await { it is Loadable.Ready } as Loadable.Ready
        assertEquals("p2", ready.value.summary.name)
        assertEquals("Body 2", ready.value.description)
        // Outlast p1's delayed reply: it must not replace p2's.
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(900) }
        assertEquals("p2", ((vm.details.value) as Loadable.Ready).value.summary.name)
    }

    @Test
    fun detailsErrorThenRetry() = runTest {
        val vm = vm()
        vm.posts.let { withContext(Dispatchers.Default) { withTimeout(10_000) { it.first { p -> p.isNotEmpty() } } } }
        vm.select("p2")
        vm.await { it is Loadable.Error }
        vm.retryDetails()
        val ready = vm.await { it is Loadable.Ready } as Loadable.Ready
        assertEquals("Body 2", ready.value.description)
    }
}
