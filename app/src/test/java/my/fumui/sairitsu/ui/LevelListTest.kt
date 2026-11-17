package my.fumui.sairitsu.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelListTest {
    @Test
    fun prefetchesSeveralScreensAhead() {
        // 12 rows on screen, 40 loaded: the user is still ~2 screens from the end.
        assertTrue(shouldLoadMore(lastVisible = 15, visibleCount = 12, size = 40, loading = false))
    }

    @Test
    fun waitsWhenFarFromEnd() {
        assertFalse(shouldLoadMore(lastVisible = 11, visibleCount = 12, size = 100, loading = false))
    }

    @Test
    fun usesMinimumDistanceBeforeLayout() {
        assertTrue(shouldLoadMore(lastVisible = 0, visibleCount = 1, size = 10, loading = false))
        assertFalse(shouldLoadMore(lastVisible = 0, visibleCount = 1, size = 30, loading = false))
    }

    @Test
    fun loadMoreKeyChangesWhenACachedPageAppendsWithinOneFrame() {
        val items = List(40) { my.fumui.sairitsu.data.model.LevelItem("l$it", "t$it") }
        val before = ListState(items = items.take(10), page = 0, pageCount = 99)
        val after = ListState(items = items, page = 3, pageCount = 99)
        // Both states want more; the key must differ so the list asks again.
        val first = loadMoreKey(lastVisible = 9, visibleCount = 10, groups = 10, list = before)
        val second = loadMoreKey(lastVisible = 12, visibleCount = 10, groups = 40, list = after)
        assertTrue(first != null && second != null && first != second)
        org.junit.Assert.assertNull(loadMoreKey(lastVisible = 9, visibleCount = 10, groups = 10, list = before.copy(loading = true)))
    }

    @Test
    fun skipsWhileLoadingOrEmpty() {
        assertFalse(shouldLoadMore(lastVisible = 39, visibleCount = 12, size = 40, loading = true))
        assertFalse(shouldLoadMore(lastVisible = -1, visibleCount = 0, size = 0, loading = false))
    }
}
