package my.fumui.sairitsu.ui

import my.fumui.sairitsu.MemoryDataStore
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.gameplay.PlayResult
import my.fumui.sairitsu.gameplay.ResourceCache
import my.fumui.sairitsu.gameplay.ResultStore
import my.fumui.sairitsu.library.CollectionStore
import my.fumui.sairitsu.library.LevelCollection
import my.fumui.sairitsu.library.LevelStats
import my.fumui.sairitsu.library.Library
import my.fumui.sairitsu.library.LibraryIndex
import my.fumui.sairitsu.library.LibraryQuery
import my.fumui.sairitsu.library.LibraryQueryStore
import my.fumui.sairitsu.library.RatingRange
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [LibraryViewModel] with collections: scope, the collection filter, multi-select and membership actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelCollectionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val queryStore = LibraryQueryStore(MemoryDataStore())
    private val results = object : ResultStore {
        override suspend fun best(server: ServerAddress, level: String): PlayResult? = null
        override suspend fun record(server: ServerAddress, level: String, result: PlayResult) = false
        override suspend fun stats() = emptyMap<LevelKey, LevelStats>()
    }
    private lateinit var library: Library
    private lateinit var collections: CollectionStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        library = Library(LibraryIndex(File(tmp.root, "library.json")), ResourceCache(tmp.newFolder("cache")))
        runBlocking {
            library.index.put(entry("a", title = "Alpha", addedAt = 3, rating = 10.0))
            library.index.put(entry("b", title = "Beta", addedAt = 2, rating = 20.0))
            library.index.put(entry("c", title = "Gamma", addedAt = 1, rating = 30.0))
        }
        collections = newStore()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newStore() =
        CollectionStore(File(tmp.root, "collections.json")) { library.index.entries.value.mapTo(HashSet()) { it.key } }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun key(name: String, server: String = "sonolus.sbuga.com") = LevelKey(server, name)

    private fun TestScope.vm(scope: PickerScope = PickerScope.All, store: LibraryQueryStore? = queryStore) =
        LibraryViewModel(library, results, store, collections = collections, scope = scope, compute = dispatcher) { e ->
            ItemDetails(LevelItem(name = e.name, title = e.title))
        }.also { advanceUntilIdle() }

    private fun TestScope.collectionVm(c: LevelCollection) = vm(PickerScope.InCollection(c.id), store = null)

    private val LibraryViewModel.ui get() = state.value

    private fun LibraryViewModel.names() = ui.visible.map { it.name }

    /** Store writes run on real IO threads: run the test dispatcher until [done] holds (5 s at most). */
    private fun TestScope.settle(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            advanceUntilIdle()
            if (done()) return
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    // Scope and the collection filter

    @Test
    fun collectionScopeShowsOnlyMembers() = test {
        val x = collections.create("x", setOf(key("a"), key("c")))
        assertEquals(listOf("a", "c"), collectionVm(x).names())
    }

    @Test
    fun collectionScopeStartsClearAndNeverSaves() = test {
        queryStore.save(LibraryQuery(text = "Beta"))
        val x = collections.create("x", setOf(key("a"), key("c")))
        val vm = collectionVm(x)
        assertEquals(LibraryQuery(), vm.ui.query)
        vm.setQuery(LibraryQuery(text = "Gamma"))
        advanceUntilIdle()
        assertEquals(listOf("c"), vm.names())
        assertEquals("Beta", queryStore.query.first().text)
    }

    @Test
    fun facetsComeFromTheScope() = test {
        val x = collections.create("x", setOf(key("a"), key("b")))
        assertEquals(RatingRange(10.0, 20.0), collectionVm(x).ui.facets.ratingRange)
    }

    @Test
    fun collectionTitleFollowsRename() = test {
        val x = collections.create("x", setOf(key("a")))
        val vm = collectionVm(x)
        assertEquals("x", vm.ui.collection?.name)
        collections.rename(x.id, "New")
        advanceUntilIdle()
        assertEquals("New", vm.ui.collection?.name)
    }

    @Test
    fun missingCollectionIsReported() = test {
        val vm = vm(PickerScope.InCollection("nope"), store = null)
        assertTrue(vm.ui.missing)
        assertEquals(emptyList<String>(), vm.names())
    }

    @Test
    fun scopeEmptyWhenCollectionHasNoLibraryMembers() = test {
        val x = collections.create("x")
        assertTrue(collectionVm(x).ui.scopeEmpty)
        assertFalse(vm().ui.scopeEmpty)
    }

    @Test
    fun playFilterByCollection() = test {
        val x = collections.create("x", setOf(key("b"), key("c")))
        val vm = vm()
        vm.setQuery(LibraryQuery(collections = setOf(x.id)))
        advanceUntilIdle()
        assertEquals(listOf("b", "c"), vm.names())
    }

    @Test
    fun playListsCollectionsSortedByName() = test {
        collections.create("beta")
        collections.create("Alpha")
        assertEquals(listOf("Alpha", "beta"), vm().ui.collections.map { it.name })
    }

    @Test
    fun staleCollectionIdIsDroppedFromQuery() = test {
        val x = collections.create("x", setOf(key("a")))
        queryStore.save(LibraryQuery(collections = setOf(x.id, "gone")))
        val vm = vm()
        assertEquals(setOf(x.id), vm.ui.query.collections)
        assertEquals(setOf(x.id), queryStore.query.first().collections)
        collections.delete(x.id)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.ui.query.collections)
        assertEquals(3, vm.ui.visible.size)
    }

    @Test
    fun deletingALevelRemovesItFromCollections() = test {
        val x = collections.create("x", setOf(key("a"), key("b")))
        val vm = vm()
        vm.delete(vm.ui.visible.first { it.name == "a" })
        settle { collections.get(x.id)!!.levels == listOf(key("b")) }
        assertEquals(listOf(key("b")), collections.get(x.id)!!.levels)
    }

    @Test
    fun deleteSelectionDeletesAllAndEndsSelection() = test {
        val x = collections.create("x", setOf(key("a"), key("b")))
        val vm = vm()
        vm.startSelection(listOf(key("a"), key("b")))
        vm.deleteSelection()!!.let { job -> settle { job.isCompleted } }
        assertEquals(emptySet<LevelKey>(), vm.ui.selection)
        settle { vm.names() == listOf("c") && collections.get(x.id)!!.levels.isEmpty() }
        assertEquals(listOf("c"), vm.names())
        assertEquals(emptyList<LevelKey>(), collections.get(x.id)!!.levels)
    }

    @Test
    fun deleteSelectionMovesDetailsToANeighbour() = test {
        val vm = vm()
        val shown = vm.ui.visible
        vm.select(shown[0])
        vm.startSelection(listOf(shown[0].key))
        vm.deleteSelection()!!.let { job -> settle { job.isCompleted } }
        settle { vm.ui.visible.size == shown.size - 1 }
        assertEquals(shown[1].key, vm.ui.selected?.key)
    }

    @Test
    fun deleteSelectionAtTheEndMovesDetailsBack() = test {
        val vm = vm()
        val shown = vm.ui.visible
        vm.select(shown[1])
        vm.startSelection(listOf(shown[1].key, shown[2].key))
        vm.deleteSelection()!!.let { job -> settle { job.isCompleted } }
        settle { vm.ui.visible.size == 1 }
        assertEquals(shown[0].key, vm.ui.selected?.key)
    }

    @Test
    fun deleteSelectionReportsFailuresOnce() = test {
        val vm = vm()
        // A directory where the index writes its temp file: every index write fails.
        File(tmp.root, "library.json.tmp").mkdir()
        vm.startSelection(listOf(key("a"), key("b")))
        vm.deleteSelection()!!.let { job -> settle { job.isCompleted } }
        settle { vm.ui.message != null }
        assertEquals(LibraryViewModel.deleteFailed(2), vm.ui.message)
        assertEquals(3, vm.ui.visible.size)
        assertEquals("Couldn't delete the level", LibraryViewModel.deleteFailed(1))
    }

    // Multi-select and membership actions

    @Test
    fun toggleByRowAndByChart() = test {
        val vm = vm()
        vm.startSelection(listOf(key("a")))
        vm.toggleSelection(listOf(key("a"), key("b")))
        assertEquals(setOf(key("a"), key("b")), vm.ui.selection)
        vm.toggleSelection(listOf(key("a"), key("b")))
        assertEquals(emptySet<LevelKey>(), vm.ui.selection)
        vm.startSelection(listOf(key("a"), key("b")))
        vm.toggleSelection(listOf(key("b")))
        assertEquals(setOf(key("a")), vm.ui.selection)
    }

    @Test
    fun selectionDoesNotChangeTheDetailsPane() = test {
        val vm = vm()
        val before = vm.ui.selected
        vm.startSelection(listOf(key("c")))
        advanceUntilIdle()
        assertEquals(before, vm.ui.selected)
    }

    @Test
    fun selectAllUsesFilteredList() = test {
        val vm = vm()
        vm.setQuery(LibraryQuery(text = "Alpha"))
        advanceUntilIdle()
        vm.selectAll()
        assertEquals(setOf(key("a")), vm.ui.selection)
        vm.selectAll()
        assertEquals(emptySet<LevelKey>(), vm.ui.selection)
    }

    @Test
    fun selectionShrinksWhenEntriesLeave() = test {
        val vm = vm()
        vm.startSelection(listOf(key("a"), key("b")))
        library.delete(key("b"))
        settle { vm.ui.visible.none { it.name == "b" } }
        assertEquals(setOf(key("a")), vm.ui.selection)
        library.delete(key("a"))
        settle { vm.ui.visible.none { it.name == "a" } }
        assertEquals(emptySet<LevelKey>(), vm.ui.selection)
    }

    @Test
    fun selectionShrinksWhenRemovedFromOpenCollection() = test {
        val x = collections.create("x", setOf(key("a"), key("b")))
        val vm = collectionVm(x)
        vm.startSelection(listOf(key("a"), key("b")))
        collections.remove(setOf(x.id), setOf(key("b")))
        advanceUntilIdle()
        assertEquals(setOf(key("a")), vm.ui.selection)
    }

    @Test
    fun selectionDistinguishesServers() = test {
        library.index.put(entry("a", server = "other.example", title = "Alpha too", addedAt = 4))
        val x = collections.create("x")
        val vm = vm()
        vm.startSelection(listOf(key("a")))
        vm.setMembership(x.id, vm.ui.selection, add = true)
        settle { collections.get(x.id)!!.levels.isNotEmpty() }
        assertEquals(listOf(key("a")), collections.get(x.id)!!.levels)
        vm.clearSelection()
        vm.setQuery(LibraryQuery(text = "Alpha"))
        advanceUntilIdle()
        vm.selectAll()
        assertEquals(setOf(key("a"), key("a", server = "other.example")), vm.ui.selection)
    }

    @Test
    fun setMembershipAddsAndRemoves() = test {
        val x = collections.create("x")
        val vm = vm()
        vm.setMembership(x.id, setOf(key("a"), key("b")), add = true)
        settle { collections.get(x.id)!!.levels.size == 2 }
        vm.setMembership(x.id, setOf(key("a")), add = false)
        settle { collections.get(x.id)!!.levels == listOf(key("b")) }
        assertEquals(listOf(key("b")), collections.get(x.id)!!.levels)
    }

    @Test
    fun createCollectionWithSelection() = test {
        val vm = vm()
        vm.createCollection("New", setOf(key("a"), key("b")))
        settle { collections.collections.value.isNotEmpty() }
        val created = collections.collections.value.single()
        assertEquals("New", created.name)
        assertEquals(setOf(key("a"), key("b")), created.levels.toSet())
    }

    @Test
    fun removeTargetInPlayNeedsExactlyOneCollection() = test {
        val x = collections.create("x", setOf(key("a")))
        val y = collections.create("y", setOf(key("b")))
        val vm = vm()
        assertNull(vm.ui.removeTarget)
        vm.setQuery(LibraryQuery(collections = setOf(x.id)))
        advanceUntilIdle()
        assertEquals(x.id, vm.ui.removeTarget?.id)
        vm.setQuery(LibraryQuery(collections = setOf(x.id, y.id)))
        advanceUntilIdle()
        assertNull(vm.ui.removeTarget)
        assertEquals(y.id, collectionVm(y).ui.removeTarget?.id)
    }

    @Test
    fun removeSelectionFromTargetEndsSelection() = test {
        val x = collections.create("x", setOf(key("a"), key("b")))
        val vm = collectionVm(x)
        vm.startSelection(listOf(key("a")))
        vm.removeSelectionFromTarget()
        settle { collections.get(x.id)!!.levels == listOf(key("b")) }
        assertEquals(emptySet<LevelKey>(), vm.ui.selection)
        assertEquals(listOf("b"), vm.names())
    }

    @Test
    fun membershipWriteFailureShowsMessage() = test {
        val x = collections.create("x")
        val vm = vm()
        File(tmp.root, "collections.json.tmp").mkdir()
        vm.setMembership(x.id, setOf(key("a")), add = true)
        settle { vm.ui.message != null }
        assertEquals(LibraryViewModel.COLLECTIONS_SAVE_FAILED, vm.ui.message)
        assertEquals(emptyList<LevelKey>(), collections.get(x.id)!!.levels)
    }

    @Test
    fun recoveredCollectionsMessageShownOnce() = test {
        File(tmp.root, "collections.json").writeText("{bad")
        collections = newStore()
        val vm = vm()
        assertEquals(LibraryViewModel.COLLECTIONS_RECOVERED, vm.ui.message)
        assertFalse(collections.recoveredFromCorruption)
        vm.messageShown()
        assertNull(vm().ui.message)
    }
}
