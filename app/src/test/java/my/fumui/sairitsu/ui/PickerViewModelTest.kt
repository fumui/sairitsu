package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SearchForms
import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.data.model.ItemInfo
import my.fumui.sairitsu.data.model.ItemList
import my.fumui.sairitsu.data.model.LevelItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeLevelSource()
    private val server = my.fumui.sairitsu.data.ServerAddress.parse("coconut.sonolus.com/horizon")!!
    private val infos = mutableListOf<my.fumui.sairitsu.data.model.ServerInfo>()
    private val requested = mutableListOf<my.fumui.sairitsu.data.ServerAddress>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.connected(): PickerViewModel {
        val vm = PickerViewModel({ requested += it; source }, server, onServerInfo = { infos += it })
        advanceUntilIdle()
        return vm
    }

    private val PickerViewModel.ui get() = state.value

    @Test
    fun connectLoadsAndAutoSelects() = test {
        val vm = connected()
        assertEquals(20, vm.ui.list.items.size)
        assertEquals("coconut-horizon-6805", vm.ui.selected?.name)
        assertTrue(vm.ui.details is Loadable.Ready)
    }

    @Test
    fun connectsToRouteServerOnInit() = test {
        val vm = connected()
        assertEquals(listOf(server), requested)
        assertEquals(server, vm.ui.server)
    }

    @Test
    fun reportsServerInfo() = test {
        connected()
        assertEquals(listOf("Coconut Horizon"), infos.map { it.title })
    }

    @Test
    fun loadMoreAppendsWithCursor() = test {
        val vm = connected()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals("6853", source.listCalls[1].second)
        assertEquals(40, vm.ui.list.items.size)
        assertTrue(vm.ui.list.endReached)
    }

    @Test
    fun loadMoreGuards() = test {
        val vm = connected()
        val pending = CompletableDeferred<ItemList>()
        source.list = { _, _ -> pending.await() }
        vm.loadMore()
        vm.loadMore()
        runCurrent()
        assertEquals(2, source.listCalls.size)
        pending.complete(FakeLevelSource.PAGE_2)
        advanceUntilIdle()
        assertTrue(vm.ui.list.endReached)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(2, source.listCalls.size)
    }

    @Test
    fun duplicateNamesAcrossPagesDropped() = test {
        val first = FakeLevelSource.PAGE_1
        val overlap = FakeLevelSource.PAGE_2.copy(items = listOf(first.items.last()) + FakeLevelSource.PAGE_2.items.take(3))
        source.list = { _, cursor -> if (cursor == null) first else overlap }
        val vm = connected()
        vm.loadMore()
        advanceUntilIdle()
        val names = vm.ui.list.items.map { it.name }
        assertEquals(names.distinct(), names)
        assertEquals(23, names.size)
    }

    @Test
    fun pageNumberFallback() = test {
        val first = FakeLevelSource.PAGE_1.copy(cursor = null, pageCount = 2)
        val second = FakeLevelSource.PAGE_2.copy(pageCount = 2)
        source.list = { params, _ -> if (params["page"] == "1") second else first }
        val vm = connected()
        assertTrue(!vm.ui.list.endReached)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals("1", source.listCalls[1].first["page"])
        assertEquals(40, vm.ui.list.items.size)
        assertTrue(vm.ui.list.endReached)
    }

    private class FakeCatalog(initial: my.fumui.sairitsu.data.CatalogState) : CatalogHandle {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
        var opens = 0
        var refreshes = 0

        override fun open() {
            opens++
        }

        override fun refresh() {
            refreshes++
        }
    }

    private fun song(name: String, title: String, rating: Double, vararg tags: String) =
        LevelItem(name, title, rating = rating, tags = tags.map { my.fumui.sairitsu.data.model.Tag(it) })

    private val saved = listOf(
        song("b-append", "B", 30.0, "#APPEND"), song("a-easy", "A", 5.0, "#EASY"),
        song("b-easy", "B", 6.0, "#EASY"), song("a-expert", "A", 25.0, "#EXPERT"),
    )

    private fun TestScope.withCatalog(cat: FakeCatalog): PickerViewModel {
        val vm = PickerViewModel({ source }, server, catalog = cat, compute = dispatcher)
        advanceUntilIdle()
        return vm
    }

    private val PickerViewModel.names get() = ui.list.items.map { it.name }

    @Test
    fun catalogListReplacesServerPaging() = test {
        val cat = FakeCatalog(my.fumui.sairitsu.data.CatalogState(saved, loaded = true, complete = true))
        val vm = withCatalog(cat)
        assertEquals(1, cat.opens)
        assertEquals(emptyList<Any>(), source.listCalls)
        // Songs together, in the order they first appear.
        assertEquals(listOf("b-append", "b-easy", "a-easy", "a-expert"), vm.names)
        assertTrue(vm.ui.list.endReached)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), source.listCalls)
        assertEquals("b-append", vm.ui.selected?.name)
    }

    @Test
    fun optedOutCatalogListsFromServer() = test {
        val cat = FakeCatalog(my.fumui.sairitsu.data.CatalogState(loaded = true))
        val vm = withCatalog(cat)
        cat.state.value = my.fumui.sairitsu.data.CatalogState(loaded = true, disabled = true)
        advanceUntilIdle()
        assertEquals(20, vm.ui.list.items.size)
        assertEquals(null, source.listCalls.first().second)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals("6853", source.listCalls.last().second)
        assertEquals(40, vm.ui.list.items.size)
        assertEquals(my.fumui.sairitsu.data.SyncStatus.Idle, vm.ui.sync)
    }

    @Test
    fun serverInfoOptOutBeatsCatalog() = test {
        source.server = {
            my.fumui.sairitsu.data.model.ServerInfo(
                "Mania",
                sairitsu = my.fumui.sairitsu.data.model.SairitsuServerFlags(levelCatalog = false),
            )
        }
        val cat = FakeCatalog(my.fumui.sairitsu.data.CatalogState())
        val vm = withCatalog(cat)
        assertEquals(20, vm.ui.list.items.size)
        assertTrue(source.listCalls.isNotEmpty())
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(40, vm.ui.list.items.size)
    }

    @Test
    fun catalogListGrowsWhileDownloading() = test {
        val cat = FakeCatalog(
            my.fumui.sairitsu.data.CatalogState(saved.take(2), loaded = true, status = my.fumui.sairitsu.data.SyncStatus.Downloading(2, 4)),
        )
        val vm = withCatalog(cat)
        assertEquals(listOf("b-append", "a-easy"), vm.names)
        assertTrue(vm.ui.list.loading)
        assertEquals(my.fumui.sairitsu.data.SyncStatus.Downloading(2, 4), vm.ui.sync)
        cat.state.value = my.fumui.sairitsu.data.CatalogState(saved, loaded = true, complete = true)
        advanceUntilIdle()
        assertEquals(4, vm.names.size)
        assertTrue(!vm.ui.list.loading)
    }

    @Test
    fun searchRunsLocallyOnceTheCatalogIsComplete() = test {
        source.info = { ItemInfo() }
        val cat = FakeCatalog(my.fumui.sairitsu.data.CatalogState(saved, loaded = true, complete = true))
        // The server also matches readings the saved list lacks: "a-expert" (saved) and "romaji-only" (not).
        source.list = { _, _ -> ItemList(pageCount = 1, items = listOf(saved[3], LevelItem("romaji-only", "R"), saved[0])) }
        val vm = withCatalog(cat)
        vm.openSearch()
        assertEquals(emptyList<Any>(), source.listCalls)
        vm.setQuickKeywords("b")
        advanceUntilIdle()
        assertEquals(listOf("b-append", "b-easy", "a-expert", "romaji-only"), vm.names)
        assertEquals("b", source.listCalls.single().first["keywords"])
        assertTrue(!vm.ui.list.loading)

        // Offline: the saved-list matches stand.
        source.list = { _, _ -> throw IOException("offline") }
        vm.setQuickKeywords("a")
        advanceUntilIdle()
        assertEquals(listOf("b-append", "b-easy", "a-easy", "a-expert"), vm.names)
        assertTrue(!vm.ui.list.loading)
    }

    @Test
    fun searchGoesToTheServerUntilTheCatalogIsComplete() = test {
        source.info = { ItemInfo() }
        val cat = FakeCatalog(my.fumui.sairitsu.data.CatalogState(saved.take(1), loaded = true))
        val vm = withCatalog(cat)
        vm.openSearch()
        advanceUntilIdle()
        assertEquals(1, source.listCalls.size)
        assertEquals(20, vm.ui.list.items.size)
        vm.closeSearch()
        advanceUntilIdle()
        assertEquals(listOf("b-append"), vm.names)
    }

    @Test
    fun searchTheCatalogCantAnswerGoesToTheServer() = test {
        val form = my.fumui.sairitsu.data.model.Search(
            "advanced", "#ADVANCED",
            options = listOf(my.fumui.sairitsu.data.model.SearchOption("genre", "#GENRE", "text", def = kotlinx.serialization.json.JsonPrimitive(""))),
        )
        source.info = { ItemInfo(searches = listOf(form)) }
        val cat = FakeCatalog(my.fumui.sairitsu.data.CatalogState(saved, loaded = true, complete = true))
        val vm = withCatalog(cat)
        vm.openSearch()
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), source.listCalls)
        vm.setOption(0, OptionState.Text("rock"))
        advanceUntilIdle()
        assertEquals("rock", source.listCalls.single().first["genre"])
    }

    @Test
    fun offlineKeepsTheSavedList() = test {
        source.info = { throw IOException("offline") }
        val cat = FakeCatalog(my.fumui.sairitsu.data.CatalogState(saved, loaded = true, complete = true))
        val vm = withCatalog(cat)
        assertTrue(vm.ui.serverInfo is Loadable.Error)
        assertEquals(4, vm.names.size)
        assertNull(vm.ui.list.error)
    }

    @Test
    fun failedFirstDownloadShowsRetry() = test {
        val cat = FakeCatalog(
            my.fumui.sairitsu.data.CatalogState(loaded = true, status = my.fumui.sairitsu.data.SyncStatus.Failed("Couldn't reach the server")),
        )
        val vm = withCatalog(cat)
        assertEquals("Couldn't reach the server\nDownloaded levels are under Play", vm.ui.list.error)
        vm.retryList()
        assertEquals(2, cat.opens)
    }

    @Test
    fun numberedPagesLoadSeveralAtOnce() = test {
        val pages = (0 until 5).map { n ->
            FakeLevelSource.PAGE_1.copy(cursor = null, pageCount = 5, items = FakeLevelSource.PAGE_1.items.map { it.copy(name = "${it.name}-$n") })
        }
        source.list = { params, _ -> pages[params["page"]?.toInt() ?: 0] }
        val vm = connected()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("1", "2", "3"), source.listCalls.drop(1).map { it.first["page"] })
        assertEquals(80, vm.ui.list.items.size)
        assertEquals(pages[3].items.last().name, vm.ui.list.items.last().name)
        assertTrue(!vm.ui.list.endReached)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals("4", source.listCalls.last().first["page"])
        assertEquals(100, vm.ui.list.items.size)
        assertTrue(vm.ui.list.endReached)
    }

    @Test
    fun firstPageDoesNotWaitForServerInfo() = test {
        val info = CompletableDeferred<my.fumui.sairitsu.data.model.ItemInfo>()
        source.info = { info.await() }
        val vm = PickerViewModel({ source }, server)
        advanceUntilIdle()
        assertEquals(20, vm.ui.list.items.size)
        info.complete(FakeLevelSource.INFO)
        advanceUntilIdle()
        assertEquals(1, source.listCalls.size)
    }

    @Test
    fun selectingPrefetchesOtherChartsOfTheSong() = test {
        val song = (1..3).map { my.fumui.sairitsu.data.model.LevelItem(name = "s$it", title = "Song", rating = it.toDouble()) }
        source.list = { _, _ -> FakeLevelSource.PAGE_1.copy(cursor = null, pageCount = 1, items = song) }
        connected()
        assertEquals(setOf("s1", "s2", "s3"), source.detailCalls.toSet())
    }

    @Test
    fun staleListDropped() = test {
        val vm = connected()
        vm.openSearch()
        advanceUntilIdle()
        val slow = CompletableDeferred<ItemList>()
        val fast = FakeLevelSource.PAGE_2
        source.list = { params, _ -> if (params["sort"] == "popular") slow.await() else fast }
        val sort = adv(vm).options.indexOfFirst { it.query == "sort" }
        vm.setOption(sort, OptionState.Select("popular"))
        runCurrent()
        vm.setOption(sort, OptionState.Select("newest"))
        runCurrent()
        slow.complete(FakeLevelSource.PAGE_1)
        advanceUntilIdle()
        assertEquals(fast.items.map { it.name }, vm.ui.list.items.map { it.name })
    }

    @Test
    fun staleDetailsDropped() = test {
        val vm = connected()
        val slowA = CompletableDeferred<ItemDetails>()
        source.details = { name -> if (name == "A") slowA.await() else ItemDetails(LevelItem(name, name)) }
        vm.select(LevelItem("A", "A"))
        runCurrent()
        vm.select(LevelItem("B", "B"))
        runCurrent()
        slowA.complete(ItemDetails(LevelItem("A", "A")))
        advanceUntilIdle()
        assertEquals("B", (vm.ui.details as Loadable.Ready).value.item.name)
    }

    @Test
    fun textOptionDebounced() = test {
        val vm = connected()
        vm.openSearch()
        advanceUntilIdle()
        val calls = source.listCalls.size
        val keywords = SearchForms.keywordsIndex(adv(vm))!!
        vm.setOption(keywords, OptionState.Text("l"))
        advanceTimeBy(100)
        vm.setOption(keywords, OptionState.Text("lo"))
        advanceTimeBy(100)
        vm.setOption(keywords, OptionState.Text("love"))
        advanceTimeBy(399)
        runCurrent()
        assertEquals(calls, source.listCalls.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(calls + 1, source.listCalls.size)
        assertEquals("love", source.listCalls.last().first["keywords"])
    }

    @Test
    fun searchModeAndClose() = test {
        val vm = connected()
        vm.openSearch()
        advanceUntilIdle()
        val search = vm.ui.search!!
        assertEquals(search.forms.indexOfFirst { it.type == "advanced" }, search.activeForm)
        val x = LevelItem("x", "X")
        vm.select(x)
        advanceUntilIdle()
        vm.closeSearch()
        advanceUntilIdle()
        assertNull(vm.ui.search)
        assertEquals(emptyMap<String, String>(), source.listCalls.last().first)
        assertEquals("x", vm.ui.selected?.name)
    }

    @Test
    fun listErrorKeepsItems() = test {
        val vm = connected()
        source.list = { _, _ -> throw IOException("offline") }
        vm.loadMore()
        advanceUntilIdle()
        assertNotNull(vm.ui.list.error)
        assertEquals(20, vm.ui.list.items.size)
        source.list = { _, _ -> FakeLevelSource.PAGE_2 }
        vm.retryList()
        advanceUntilIdle()
        assertEquals("6853", source.listCalls.last().second)
        assertEquals(40, vm.ui.list.items.size)
    }

    @Test
    fun sectionChipShowsEmbeddedItems() = test {
        val vm = connected()
        val calls = source.listCalls.size
        vm.selectSection(1)
        advanceUntilIdle()
        assertEquals(5, vm.ui.list.items.size)
        assertTrue(vm.ui.list.endReached)
        assertEquals(calls, source.listCalls.size)
    }

    @Test
    fun connectFailureShowsRetry() = test {
        source.info = { throw IOException("offline") }
        val vm = connected()
        assertTrue(vm.ui.serverInfo is Loadable.Error)
        assertEquals("Couldn't reach the server\nDownloaded levels are under Play", vm.ui.list.error)
        source.info = { FakeLevelSource.INFO }
        vm.retryList()
        advanceUntilIdle()
        assertEquals(20, vm.ui.list.items.size)
    }

    @Test
    fun quickSearchWhenNoForms() = test {
        source.info = { ItemInfo() }
        val vm = connected()
        vm.openSearch()
        advanceUntilIdle()
        assertEquals(-1, vm.ui.search!!.activeForm)
        vm.setQuickKeywords("love")
        advanceTimeBy(401)
        runCurrent()
        assertEquals(mapOf("type" to "quick", "keywords" to "love"), source.listCalls.last().first)
    }

    @Test
    fun clearFiltersKeepsKeywordsAndSort() = test {
        val vm = connected()
        vm.openSearch()
        advanceUntilIdle()
        val form = adv(vm)
        val keywords = SearchForms.keywordsIndex(form)!!
        val sort = form.options.indexOfFirst { it.query == "sort" }
        val author = form.options.indexOfFirst { it.query == "author" }
        vm.setOption(keywords, OptionState.Text("love"))
        vm.setOption(sort, OptionState.Select("popular"))
        vm.setOption(author, OptionState.Text("someone"))
        advanceUntilIdle()
        vm.clearFilters()
        advanceUntilIdle()
        val params = source.listCalls.last().first
        assertEquals("love", params["keywords"])
        assertEquals("popular", params["sort"])
        assertNull(params["author"])
    }

    @Test
    fun switchingFormCarriesKeywords() = test {
        val quick = my.fumui.sairitsu.data.model.Search(
            type = "quick",
            title = "#KEYWORDS",
            options = listOf(my.fumui.sairitsu.data.model.SearchOption(query = "keywords", name = "#KEYWORDS", type = "text")),
        )
        source.info = { FakeLevelSource.INFO.copy(searches = FakeLevelSource.INFO.searches + quick) }
        val vm = connected()
        vm.openSearch()
        advanceUntilIdle()
        vm.setOption(SearchForms.keywordsIndex(adv(vm))!!, OptionState.Text("love"))
        advanceUntilIdle()
        vm.selectForm(vm.ui.search!!.forms.indexOfFirst { it.type == "quick" })
        advanceUntilIdle()
        assertEquals(mapOf("type" to "quick", "keywords" to "love"), source.listCalls.last().first)
    }

    @Test
    fun selectionToggleAndSelectAll() = test {
        val vm = connected()
        val names = vm.ui.list.items.map { it.name }
        vm.startSelection(listOf(names[0]))
        vm.toggleSelection(names.take(2))
        assertEquals(names.take(2).toSet(), vm.ui.selection)
        vm.toggleSelection(names.take(2))
        assertEquals(emptySet<String>(), vm.ui.selection)
        vm.selectAll()
        assertEquals(names.toSet(), vm.ui.selection)
        vm.selectAll()
        assertEquals(emptySet<String>(), vm.ui.selection)
        vm.startSelection(listOf(names[0]))
        vm.clearSelection()
        assertEquals(emptySet<String>(), vm.ui.selection)
    }

    @Test
    fun selectionClearsWhenTheListReloads() = test {
        val vm = connected()
        vm.startSelection(listOf(vm.ui.list.items[0].name))
        vm.openSearch()
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.ui.selection)
    }

    private fun adv(vm: PickerViewModel) = vm.ui.search!!.let { it.forms[it.activeForm] }
}
