package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.ItemPage
import my.fumui.sairitsu.data.PageToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ItemPagerTest {
    private fun item(name: String) = JsonObject(mapOf("name" to JsonPrimitive(name)))

    @Test
    fun loadsPagesUntilEnd() = runTest {
        val tokens = mutableListOf<PageToken?>()
        val pager = ItemPager(backgroundScope) { token ->
            tokens += token
            if (token == null) ItemPage(listOf(item("a")), PageToken.Page(1)) else ItemPage(listOf(item("b")), null)
        }
        pager.loadMore()
        runCurrent()
        pager.loadMore()
        runCurrent()
        assertEquals(listOf(item("a"), item("b")), pager.state.value.items)
        assertTrue(pager.state.value.end)
        assertEquals(listOf(null, PageToken.Page(1)), tokens)

        pager.loadMore()
        runCurrent()
        assertEquals(2, tokens.size)
    }

    @Test
    fun repeatedAndNamelessItemsAreDropped() = runTest {
        val pager = ItemPager(backgroundScope) { token ->
            if (token == null) {
                ItemPage(listOf(item("a"), item("b")), PageToken.Page(1))
            } else {
                ItemPage(listOf(item("b"), JsonObject(mapOf("title" to JsonPrimitive("x"))), item("c")), null)
            }
        }
        pager.loadMore()
        runCurrent()
        pager.loadMore()
        runCurrent()
        assertEquals(listOf(item("a"), item("b"), item("c")), pager.state.value.items)
    }

    @Test
    fun errorStopsUntilRetry() = runTest {
        val tokens = mutableListOf<PageToken?>()
        var fail = true
        val pager = ItemPager(backgroundScope) { token ->
            tokens += token
            if (fail) throw IOException("down")
            ItemPage(listOf(item("a")), null)
        }
        pager.loadMore()
        runCurrent()
        assertNotNull(pager.state.value.error)
        assertFalse(pager.state.value.loading)

        pager.loadMore()
        runCurrent()
        assertEquals(1, tokens.size)

        fail = false
        pager.retry()
        runCurrent()
        assertEquals(listOf(null, null), tokens)
        assertNull(pager.state.value.error)
        assertEquals(listOf(item("a")), pager.state.value.items)
    }
}
