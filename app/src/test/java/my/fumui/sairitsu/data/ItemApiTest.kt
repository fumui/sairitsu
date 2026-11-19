package my.fumui.sairitsu.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ItemApiTest {
    private lateinit var web: MockWebServer
    private lateinit var api: ItemApi

    @Before
    fun setUp() {
        web = MockWebServer()
        web.start()
        api = ItemApi(ServerAddress(web.url("/")), OkHttpClient())
    }

    @After
    fun tearDown() {
        runCatching { web.shutdown() }
    }

    private fun reply(body: String) = web.enqueue(MockResponse().setBody(body))

    @Test
    fun pagePagination() = runTest {
        reply("""{"pageCount":2,"items":[{"name":"a"}]}""")
        reply("""{"pageCount":2,"items":[{"name":"b"}]}""")
        val first = api.list("posts", null)
        assertEquals("/sonolus/posts/list", web.takeRequest().path)
        assertEquals(PageToken.Page(1), first.next)
        assertEquals(1, first.items.size)

        val second = api.list("posts", PageToken.Page(1))
        assertEquals("/sonolus/posts/list?page=1", web.takeRequest().path)
        assertNull(second.next)
    }

    @Test
    fun cursorPagination() = runTest {
        reply("""{"pageCount":-1,"cursor":"c1","items":[{"name":"a"}]}""")
        reply("""{"pageCount":-1,"items":[{"name":"b"}]}""")
        val first = api.list("skins", null)
        web.takeRequest()
        assertEquals(PageToken.Cursor("c1"), first.next)

        val second = api.list("skins", first.next)
        assertEquals("/sonolus/skins/list?cursor=c1", web.takeRequest().path)
        assertNull(second.next)
    }

    @Test
    fun cursorIsFollowedWhenPageCountIsPositive() = runTest {
        reply("""{"pageCount":3,"cursor":"c1","items":[{"name":"a"}]}""")
        assertEquals(PageToken.Cursor("c1"), api.list("skins", null).next)
    }

    @Test
    fun blankCursorEnds() = runTest {
        reply("""{"pageCount":-1,"cursor":"","items":[{"name":"a"}]}""")
        reply("""{"pageCount":1,"cursor":"","items":[{"name":"a"}]}""")
        assertNull(api.list("skins", null).next)
        assertNull(api.list("skins", null).next)
    }

    @Test
    fun emptyCursorPageEnds() = runTest {
        reply("""{"pageCount":-1,"cursor":"c2","items":[]}""")
        assertNull(api.list("skins", null).next)
    }

    @Test
    fun detailsReturnsObject() = runTest {
        reply("""{"item":{"name":"a"},"description":"hello"}""")
        val details = api.details("posts", "a")
        assertEquals("/sonolus/posts/a", web.takeRequest().path)
        assertEquals(JsonPrimitive("hello"), details["description"])
    }

    @Test
    fun httpErrorIsApiException() = runTest {
        web.enqueue(MockResponse().setResponseCode(404))
        try {
            api.details("posts", "missing")
            fail("expected ServerApiException")
        } catch (e: ServerApiException) {
            assertEquals(404, e.code)
        }
    }
}
