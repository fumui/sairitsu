package my.fumui.sairitsu.ui

import my.fumui.sairitsu.MemoryDataStore
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.gameplay.Grade
import my.fumui.sairitsu.gameplay.PlayResult
import my.fumui.sairitsu.gameplay.ResourceCache
import my.fumui.sairitsu.gameplay.ResultStore
import my.fumui.sairitsu.gameplay.RunHistory
import my.fumui.sairitsu.gameplay.RunRecord
import my.fumui.sairitsu.gameplay.RunSort
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import my.fumui.sairitsu.library.Library
import my.fumui.sairitsu.library.LibraryIndex
import my.fumui.sairitsu.library.LibraryQuery
import my.fumui.sairitsu.library.LibraryQueryStore
import my.fumui.sairitsu.library.LevelStats
import my.fumui.sairitsu.library.PlayedFilter
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
class LibraryViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val queryStore = LibraryQueryStore(MemoryDataStore())
    private var stats: Map<LevelKey, LevelStats> = emptyMap()
    private val results = object : ResultStore {
        override suspend fun best(server: ServerAddress, level: String): PlayResult? = null
        override suspend fun record(server: ServerAddress, level: String, result: PlayResult) = false
        override suspend fun stats() = this@LibraryViewModelTest.stats
    }
    private lateinit var library: Library

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        library = Library(LibraryIndex(File(tmp.root, "library.json")), ResourceCache(tmp.newFolder("cache")))
        runBlocking {
            library.index.put(entry("a", title = "Alpha", addedAt = 3))
            library.index.put(entry("b", title = "Beta", addedAt = 2))
            library.index.put(entry("c", title = "Gamma", addedAt = 1))
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.vm() = LibraryViewModel(library, results, queryStore) { e ->
        ItemDetails(LevelItem(name = e.name, title = e.title), description = "About ${e.name}")
    }.also { advanceUntilIdle() }

    private val LibraryViewModel.ui get() = state.value

    /** Library writes run on real IO threads: run the test dispatcher until [done] holds (5 s at most). */
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
    fun autoSelectsFirst() = test {
        val vm = vm()
        assertEquals(listOf("a", "b", "c"), vm.ui.visible.map { it.name })
        assertEquals("a", vm.ui.selected?.name)
        assertEquals("About a", (vm.ui.details as Loadable.Ready).value.description)
    }

    @Test
    fun visibleFollowsQuery() = test {
        val vm = vm()
        vm.setQuery(LibraryQuery(text = "beta"))
        advanceUntilIdle()
        assertEquals(listOf("b"), vm.ui.visible.map { it.name })
        assertEquals("b", vm.ui.selected?.name)
    }

    @Test
    fun searchFindsReadingsInTheDescription() = test {
        val vm = LibraryViewModel(library, results, queryStore) { e ->
            val reading = if (e.name == "b") "\n\nプレデター ぷれでたー puredetaa pu redetaa" else ""
            ItemDetails(LevelItem(name = e.name, title = e.title), description = "#AUTHOR:#SEPARATOR_COLON:x$reading")
        }.also { advanceUntilIdle() }
        vm.setQuery(LibraryQuery(text = "puredetaa"))
        advanceUntilIdle()
        assertEquals(listOf("b"), vm.ui.visible.map { it.name })
    }

    @Test
    fun selectLoadsDetails() = test {
        val vm = vm()
        vm.select(vm.ui.visible[2])
        advanceUntilIdle()
        assertEquals("c", vm.ui.selected?.name)
        assertEquals("About c", (vm.ui.details as Loadable.Ready).value.description)
    }

    @Test
    fun deleteSelectsNextAndCallsLibrary() = test {
        val vm = vm()
        vm.select(vm.ui.visible[1])
        advanceUntilIdle()
        val gone = vm.ui.visible[1].key
        vm.delete(vm.ui.visible[1])
        settle { library.index.get(gone) == null && vm.ui.visible.none { it.key == gone } }
        advanceUntilIdle()
        assertEquals(listOf("a", "c"), vm.ui.visible.map { it.name })
        assertEquals("c", vm.ui.selected?.name)
        assertEquals(null, library.index.get(LevelKey("sonolus.sbuga.com", "b")))
    }

    @Test
    fun deletingTheLastSelectsThePrevious() = test {
        val vm = vm()
        vm.select(vm.ui.visible[2])
        advanceUntilIdle()
        val gone = vm.ui.visible[2].key
        vm.delete(vm.ui.visible[2])
        settle { library.index.get(gone) == null && vm.ui.visible.none { it.key == gone } }
        advanceUntilIdle()
        assertEquals("b", vm.ui.selected?.name)
    }

    @Test
    fun queryIsSavedAndRestored() = test {
        val vm = vm()
        val q = LibraryQuery(text = "gam")
        vm.setQuery(q)
        advanceUntilIdle()
        assertEquals(q, queryStore.query.first())
        val again = vm()
        assertEquals(q, again.ui.query)
        assertEquals(listOf("c"), again.ui.visible.map { it.name })
    }

    @Test
    fun recoveredMessageShownOnce() = test {
        File(tmp.root, "broken.json").writeText("{bad")
        library = Library(LibraryIndex(File(tmp.root, "broken.json")), ResourceCache(tmp.newFolder("cache2")))
        val vm = vm()
        assertTrue(vm.ui.showRecoveredMessage)
        vm.recoveredMessageShown()
        assertFalse(vm.ui.showRecoveredMessage)
        assertEquals("No levels", vm.ui.visible.size.let { if (it == 0) "No levels" else "$it" })
    }

    @Test
    fun recoveredMessageNotRepeatedForANewViewModel() = test {
        File(tmp.root, "broken.json").writeText("{bad")
        library = Library(LibraryIndex(File(tmp.root, "broken.json")), ResourceCache(tmp.newFolder("cache2")))
        vm().recoveredMessageShown()
        assertFalse(vm().ui.showRecoveredMessage)
    }

    @Test
    fun deleteFailureShowsMessageInsteadOfCrashing() = test {
        val vm = vm()
        // The index can't be written (a disk-full stand-in): library.json is now a non-empty directory.
        val file = File(tmp.root, "library.json")
        file.delete()
        File(file, "x").apply { parentFile!!.mkdirs() }.writeText("x")
        vm.delete(vm.ui.visible[0])
        settle { vm.ui.message != null }
        assertEquals("Couldn't delete the level", vm.ui.message)
        assertEquals(3, vm.ui.visible.size)
        vm.messageShown()
        assertEquals(null, vm.ui.message)
    }

    @Test
    fun refreshStatsUpdatesPlayedFilter() = test {
        val vm = vm()
        vm.setQuery(LibraryQuery(played = PlayedFilter.Played))
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.ui.visible.map { it.name })
        stats = mapOf(LevelKey("sonolus.sbuga.com", "b") to LevelStats(PlayResult(Grade.Pass, 1, 0, 0, 0, 0, 0, 0, 0)))
        vm.refreshStats()
        advanceUntilIdle()
        assertEquals(listOf("b"), vm.ui.visible.map { it.name })
    }

    private fun key(name: String) = LevelKey("sonolus.sbuga.com", name)

    private fun run(arcade: Int, at: Long?) = RunRecord(at, PlayResult(Grade.Pass, arcade, 0, 0, 0, 0, 0, 0, 0))

    private class FakeHistory(
        var data: Map<LevelKey, List<RunRecord>>,
        var fail: Boolean = false,
        val gates: MutableMap<LevelKey, CompletableDeferred<Unit>> = mutableMapOf(),
    ) : RunHistory {
        override suspend fun runs(key: LevelKey): List<RunRecord>? {
            gates[key]?.await()
            if (fail) throw IOException("unreadable")
            return data[key]
        }

        override suspend fun append(key: LevelKey, record: RunRecord, seed: PlayResult?) = Unit

        override suspend fun attachReplay(key: LevelKey, at: Long, replay: String) = false
    }

    private fun TestScope.vm(history: RunHistory) = LibraryViewModel(library, results, queryStore, history) { e ->
        ItemDetails(LevelItem(name = e.name, title = e.title), description = "About ${e.name}")
    }.also { advanceUntilIdle() }

    private val twoRuns = mapOf(key("a") to listOf(run(900, at = 1), run(950, at = 2)))

    @Test
    fun selectLoadsRunsSortedByArcade() = test {
        val vm = vm(FakeHistory(twoRuns))
        assertEquals(listOf(950, 900), vm.ui.runs.map { it.result.arcadeScore })
    }

    @Test
    fun setRunSortReorders() = test {
        val vm = vm(FakeHistory(twoRuns))
        vm.setRunSort(RunSort.Date)
        assertEquals(listOf(2L, 1L), vm.ui.runs.map { it.at })
        assertEquals(RunSort.Date, vm.ui.runSort)
    }

    @Test
    fun runSortKeptAcrossSelection() = test {
        val vm = vm(FakeHistory(mapOf(key("b") to listOf(run(999, at = 1), run(100, at = 5)))))
        vm.setRunSort(RunSort.Date)
        vm.select(vm.ui.visible.first { it.name == "b" })
        advanceUntilIdle()
        assertEquals(listOf(5L, 1L), vm.ui.runs.map { it.at })
    }

    @Test
    fun noHistoryShowsStoredBestUndated() = test {
        val best = PlayResult(Grade.FullCombo, 980000, 990000, 10, 10, 0, 0, 0, 10)
        stats = mapOf(key("a") to LevelStats(best))
        val vm = vm(FakeHistory(emptyMap()))
        assertEquals(listOf(RunRecord(null, best)), vm.ui.runs)
        vm.select(vm.ui.visible.first { it.name == "b" })
        advanceUntilIdle()
        assertEquals(emptyList<RunRecord>(), vm.ui.runs)
    }

    @Test
    fun refreshStatsReloadsRuns() = test {
        val history = FakeHistory(twoRuns)
        val vm = vm(history)
        history.data = mapOf(key("a") to twoRuns.getValue(key("a")) + run(990, at = 3))
        vm.refreshStats()
        advanceUntilIdle()
        assertEquals(listOf(990, 950, 900), vm.ui.runs.map { it.result.arcadeScore })
    }

    @Test
    fun historyReadFailureShowsNoRuns() = test {
        val vm = vm(FakeHistory(twoRuns, fail = true))
        assertEquals(emptyList<RunRecord>(), vm.ui.runs)
        assertTrue(vm.ui.details is Loadable.Ready)
    }

    @Test
    fun staleRunsLoadDoesNotOverwriteNewSelection() = test {
        val gate = CompletableDeferred<Unit>()
        val history = FakeHistory(twoRuns + (key("b") to listOf(run(123, at = 9))), gates = mutableMapOf(key("a") to gate))
        val vm = vm(history)
        vm.select(vm.ui.visible.first { it.name == "b" })
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(123), vm.ui.runs.map { it.result.arcadeScore })
    }

    @Test
    fun facetsComeFromTheWholeLibrary() = test {
        val vm = vm()
        vm.setQuery(LibraryQuery(text = "alpha"))
        advanceUntilIdle()
        assertEquals(listOf("sonolus.sbuga.com"), vm.ui.facets.servers.map { it.first })
        assertEquals(3, library.index.entries.value.size)
    }
}
