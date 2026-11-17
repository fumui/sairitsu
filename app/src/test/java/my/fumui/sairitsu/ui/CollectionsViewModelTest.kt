package my.fumui.sairitsu.ui

import my.fumui.sairitsu.MemoryDataStore
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.gameplay.ResourceCache
import my.fumui.sairitsu.library.CollectionStore
import my.fumui.sairitsu.library.Library
import my.fumui.sairitsu.library.LibraryIndex
import my.fumui.sairitsu.library.LibraryQuery
import my.fumui.sairitsu.library.LibraryQueryStore
import my.fumui.sairitsu.library.entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val queryStore = LibraryQueryStore(MemoryDataStore())
    private lateinit var library: Library
    private lateinit var store: CollectionStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        library = Library(LibraryIndex(File(tmp.root, "library.json")), ResourceCache(tmp.newFolder("cache")))
        runBlocking {
            library.index.put(entry("a"))
            library.index.put(entry("b"))
        }
        store = newStore()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newStore() =
        CollectionStore(File(tmp.root, "collections.json")) { library.index.entries.value.mapTo(HashSet()) { it.key } }

    private fun key(name: String) = LevelKey("sonolus.sbuga.com", name)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.vm() = CollectionsViewModel(store, library.index.entries, queryStore).also { advanceUntilIdle() }

    private fun TestScope.settle(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            advanceUntilIdle()
            if (done()) return
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun cardsFollowStoreAndLibrary() = test {
        val y = store.create("y", setOf(key("a")))
        store.create("X")
        val vm = vm()
        assertEquals(listOf("X", "y"), vm.state.value.cards.map { it.name })
        assertEquals(listOf(0, 1), vm.state.value.cards.map { it.count })
        store.add(setOf(y.id), setOf(key("b")))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.cards.first { it.id == y.id }.count)
    }

    @Test
    fun deleteDropsIdFromSavedPlayQuery() = test {
        val x = store.create("x")
        val y = store.create("y")
        queryStore.save(LibraryQuery(text = "t", collections = setOf(x.id, y.id)))
        val vm = vm()
        vm.delete(x.id)
        settle { store.get(x.id) == null }
        advanceUntilIdle()
        assertEquals(LibraryQuery(text = "t", collections = setOf(y.id)), queryStore.query.first())
    }

    @Test
    fun createAndRename() = test {
        val vm = vm()
        vm.create("A")
        settle { store.collections.value.isNotEmpty() }
        val a = store.collections.value.single()
        assertEquals("A", a.name)
        assertTrue(a.levels.isEmpty())
        vm.rename(a.id, "B")
        settle { store.get(a.id)?.name == "B" }
        assertEquals("B", store.get(a.id)?.name)
    }

    @Test
    fun writeFailureShowsMessage() = test {
        val vm = vm()
        File(tmp.root, "collections.json.tmp").mkdir()
        vm.create("A")
        settle { vm.state.value.message != null }
        assertEquals("Couldn't save collections", vm.state.value.message)
        vm.messageShown()
        assertEquals(null, vm.state.value.message)
    }

    @Test
    fun recoveredMessageShownOnce() = test {
        File(tmp.root, "collections.json").writeText("{bad")
        store = newStore()
        val vm = vm()
        assertEquals("The collections list was damaged and has been reset", vm.state.value.message)
        assertFalse(store.recoveredFromCorruption)
    }
}
