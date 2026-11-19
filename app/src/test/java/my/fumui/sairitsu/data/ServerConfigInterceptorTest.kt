package my.fumui.sairitsu.data

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ServerConfigInterceptorTest {
    private val web = MockWebServer()
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        web.start()
        val host = "127.0.0.1:${web.port}"
        val configs = mapOf(
            "$host/horizon" to mapOf("genre" to "rock"),
            "$host/pjsekai" to mapOf("genre" to "pop"),
            "$host/a" to mapOf("genre" to "outer"),
            "$host/a/b" to mapOf("genre" to "inner"),
        )
        client = OkHttpClient.Builder().addInterceptor(ServerConfigInterceptor { configs }).build()
    }

    @After
    fun tearDown() = web.shutdown()

    private fun get(path: String): HttpUrl {
        web.enqueue(MockResponse().setBody("{}"))
        client.newCall(Request.Builder().url(web.url(path)).build()).execute().close()
        return web.takeRequest().requestUrl!!
    }

    @Test
    fun addsValuesToInfoListAndDetails() {
        assertEquals("rock", get("/horizon/sonolus/info").queryParameter("genre"))
        val list = get("/horizon/sonolus/levels/list?page=1")
        assertEquals("rock", list.queryParameter("genre"))
        assertEquals("1", list.queryParameter("page"))
        assertEquals("rock", get("/horizon/sonolus/skins/abc").queryParameter("genre"))
    }

    @Test
    fun leavesResourceFilesAlone() {
        assertNull(get("/horizon/sonolus/repository/abcdef").queryParameter("genre"))
        assertNull(get("/horizon/cover.png").queryParameter("genre"))
    }

    @Test
    fun otherServerOnSameHostGetsItsOwn() {
        assertEquals("pop", get("/pjsekai/sonolus/levels/list").queryParameter("genre"))
    }

    @Test
    fun pathPrefixMustEndAtSegment() {
        assertNull(get("/horizon2/sonolus/info").queryParameter("genre"))
    }

    @Test
    fun existingParameterWins() {
        assertEquals(listOf("jazz"), get("/horizon/sonolus/levels/list?genre=jazz").queryParameterValues("genre"))
    }

    @Test
    fun unknownItemTypeAlone() {
        assertNull(get("/horizon/sonolus/foo/bar").queryParameter("genre"))
    }

    @Test
    fun longestMatchingServerWins() {
        assertEquals(listOf("inner"), get("/a/b/sonolus/info").queryParameterValues("genre"))
        assertEquals(listOf("outer"), get("/a/sonolus/info").queryParameterValues("genre"))
    }

    @Test
    fun unknownServerUntouched() {
        val url = get("/other/sonolus/info")
        assertEquals("/other/sonolus/info", url.encodedPath)
        assertNull(url.encodedQuery)
    }
}
