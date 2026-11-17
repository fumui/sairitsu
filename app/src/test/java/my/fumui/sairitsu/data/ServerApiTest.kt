package my.fumui.sairitsu.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ServerApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name.json")!!.readText()

    private fun api(session: () -> String? = { null }) =
        ServerApi(ServerAddress(server.url("/")), OkHttpClient(), session)

    @Test
    fun listRequestPathAndQuery() = runTest {
        server.enqueue(MockResponse().setBody(fixture("list")))
        val list = api().levelList(mapOf("type" to "quick", "keywords" to "a b"), "6853")
        val url = server.takeRequest().requestUrl!!
        assertEquals("/sonolus/levels/list", url.encodedPath)
        assertEquals("quick", url.queryParameter("type"))
        assertEquals("a b", url.queryParameter("keywords"))
        assertEquals("6853", url.queryParameter("cursor"))
        assertEquals("a%20b", url.encodedQuery!!.substringAfter("keywords=").substringBefore("&"))
        assertEquals(20, list.items.size)
    }

    @Test
    fun detailsEncodesName() = runTest {
        server.enqueue(MockResponse().setBody(fixture("details")))
        api().levelDetails("a/b c")
        assertEquals("/sonolus/levels/a%2Fb%20c", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun sessionHeaderAdded() = runTest {
        server.enqueue(MockResponse().setBody(fixture("info")))
        server.enqueue(MockResponse().setBody(fixture("info")))
        api { "s1" }.levelInfo()
        assertEquals("s1", server.takeRequest().getHeader("Sonolus-Session"))
        api().levelInfo()
        assertNull(server.takeRequest().getHeader("Sonolus-Session"))
    }

    @Test
    fun non2xxThrows() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        try {
            api().serverInfo()
            fail("expected ServerApiException")
        } catch (e: ServerApiException) {
            assertEquals(404, e.code)
        }
    }
}
