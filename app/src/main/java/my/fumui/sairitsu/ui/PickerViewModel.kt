package my.fumui.sairitsu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.data.CatalogState
import my.fumui.sairitsu.data.LevelSource
import my.fumui.sairitsu.data.SyncStatus
import my.fumui.sairitsu.text.Localizer
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SearchForms
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ServerInfo
import my.fumui.sairitsu.data.ServerApiException
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.ServerJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException

/** The server's saved level list as the browser uses it (see [my.fumui.sairitsu.data.LevelCatalog]). */
interface CatalogHandle {
    val state: StateFlow<CatalogState>

    /** Loads the saved list and syncs it with the server in the background. */
    fun open()

    /** Downloads the whole list again. */
    fun refresh()
}

/**
 * Browses one [server] (from the Online Servers list); [onServerInfo] gets its info each time it loads.
 * With a [catalog] the "All" list and (once the catalog is complete) searches run on the saved levels;
 * searches that can't be answered locally, and sections, still go to the server.
 */
class PickerViewModel(
    private val apiFactory: (ServerAddress) -> LevelSource,
    server: ServerAddress,
    private val onServerInfo: (ServerInfo) -> Unit = {},
    private val catalog: CatalogHandle? = null,
    /** Where catalog filtering runs (tests pass their own). */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(PickerUiState())
    val state: StateFlow<PickerUiState> = _state.asStateFlow()

    private val localSearch = LocalSearch(Localizer(Localizer.serverLanguage(Locale.getDefault())))

    /** Whether the list on screen comes from the catalog (and follows its updates). */
    private var listFromCatalog = false

    private var source: LevelSource? = null
    private var connectJob: Job? = null
    private var listJob: Job? = null
    private var detailsJob: Job? = null
    private var debounceJob: Job? = null

    /** Bumped for every new list query; responses from older queries are dropped. */
    private var generation = 0

    /** Levels whose details [prefetchSiblings] already asked for. */
    private val prefetched = HashSet<String>()

    init {
        connectTo(server, server.linkAddress)
        catalog?.let { handle ->
            viewModelScope.launch {
                handle.state.collect { cat ->
                    _state.update { it.copy(sync = cat.status, catalogCount = cat.items.size, catalogComplete = cat.complete) }
                    if (listFromCatalog) showLocal(generation, keepList = true)
                }
            }
        }
    }

    /** Tries the saved level list's sync again after it failed. */
    fun retrySync() {
        catalog?.open()
    }

    /** Downloads the server's whole level list again (picks up edited and removed levels). */
    fun refreshCatalog() {
        catalog?.refresh()
    }

    fun selectSection(index: Int?) {
        _state.update { it.copy(activeSection = index) }
        if (_state.value.search == null) loadFirstPage()
    }

    fun select(level: LevelItem) = selectLevel(level)

    fun loadMore() {
        val s = _state.value
        val list = s.list
        if (listFromCatalog) return
        if (list.loading || list.endReached || list.error != null || list.items.isEmpty()) return
        if (s.search == null && s.activeSection != null) return
        _state.update { it.copy(list = list.copy(loading = true)) }
        if (list.cursor != null) {
            fetchPages(generation, currentParams(), list.cursor, listOf(list.page), append = true)
        } else {
            // Page-numbered servers: fetch the next few pages at once (each request is ~1 s, mostly server time).
            val next = list.page + 1
            val last = if (list.pageCount > 0) minOf(list.pageCount - 1, next + PARALLEL_PAGES - 1) else next
            fetchPages(generation, currentParams(), null, (next..maxOf(next, last)).toList(), append = true)
        }
    }

    fun retryList() {
        val s = _state.value
        if (s.serverInfo is Loadable.Error) {
            s.server?.let { connectTo(it, s.serverInput) }
            return
        }
        if (listFromCatalog) {
            catalog?.open()
            return
        }
        if (s.list.items.isEmpty()) {
            loadFirstPage()
        } else {
            _state.update { it.copy(list = it.list.copy(error = null)) }
            loadMore()
        }
    }

    fun retryDetails() {
        _state.value.selected?.let { selectLevel(it) }
    }

    fun openSearch() {
        if (_state.value.search != null) return
        val forms = _state.value.searches
        val search = SearchState(
            forms = forms,
            activeForm = SearchForms.defaultForm(forms),
            values = forms.map(SearchForms::defaults),
        )
        _state.update { it.copy(search = search) }
        loadFirstPage()
    }

    fun closeSearch() {
        if (_state.value.search == null) return
        _state.update { it.copy(search = null) }
        loadFirstPage()
    }

    /** Switches search type, carrying the typed keywords over when both forms have them. */
    fun selectForm(index: Int) {
        updateSearch { search ->
            if (index == search.activeForm || index !in search.forms.indices) return@updateSearch search
            val values = search.values.toMutableList()
            val from = search.forms.getOrNull(search.activeForm)?.let(SearchForms::keywordsIndex)
            val to = SearchForms.keywordsIndex(search.forms[index])
            val text = from?.let { values[search.activeForm][it] as? OptionState.Text }
            if (text != null && to != null) values[index] = values[index].toMutableList().also { it[to] = text }
            search.copy(activeForm = index, values = values)
        }
        loadFirstPage()
    }

    fun setOption(optionIndex: Int, value: OptionState) {
        updateSearch { search ->
            val values = search.values.toMutableList()
            values[search.activeForm] = values[search.activeForm].toMutableList().also { it[optionIndex] = value }
            search.copy(values = values)
        }
        if (value is OptionState.Text) debounceReload() else loadFirstPage()
    }

    /** Resets the active form's filters; keywords and sort are kept. */
    fun clearFilters() {
        updateSearch { search ->
            val form = search.forms.getOrNull(search.activeForm) ?: return@updateSearch search
            val values = search.values.toMutableList()
            values[search.activeForm] = SearchForms.clearFilters(form, values[search.activeForm])
            search.copy(values = values)
        }
        loadFirstPage()
    }

    fun setQuickKeywords(text: String) {
        updateSearch { it.copy(quickKeywords = text) }
        debounceReload()
    }

    private fun connectTo(address: ServerAddress, input: String) {
        listOf(connectJob, listJob, detailsJob, debounceJob).forEach { it?.cancel() }
        generation++
        val api = apiFactory(address)
        source = api
        _state.value = PickerUiState(
            serverInput = input,
            server = address,
            serverInfo = Loadable.Loading,
            list = ListState(loading = true),
        )
        if (catalog != null) {
            catalog.open()
            showLocal(generation, keepList = false)
        } else {
            // The first page (All, no search) needs nothing from the server info: fetch it alongside.
            listFromCatalog = false
            fetchPages(generation, emptyMap(), cursor = null, pages = listOf(0), append = false)
        }
        connectJob = viewModelScope.launch {
            try {
                val (info, levels) = coroutineScope {
                    val info = async { api.serverInfo() }
                    val levels = async { api.levelInfo() }
                    info.await() to levels.await()
                }
                _state.update {
                    it.copy(serverInfo = Loadable.Ready(info), sections = levels.sections, searches = levels.searches)
                }
                onServerInfo(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = errorText(e)
                if (listFromCatalog) {
                    // The saved list still works offline; only sections and server search are missing.
                    _state.update { it.copy(serverInfo = Loadable.Error(message)) }
                    return@launch
                }
                listJob?.cancel()
                generation++
                _state.update {
                    it.copy(serverInfo = Loadable.Error(message), list = ListState(error = "$message\n$OFFLINE_HINT"))
                }
            }
        }
    }

    /**
     * Shows the catalog's levels (filtered by the open search) as the list. A search the catalog can't
     * answer, or any search before the catalog is complete, goes to the server instead.
     * [keepList] leaves the current list up while recomputing (catalog updates).
     */
    private fun showLocal(gen: Int, keepList: Boolean) {
        val handle = catalog ?: return
        val search = _state.value.search
        if (search != null && !handle.state.value.complete) {
            listFromCatalog = false
            if (!keepList) startServerList(gen)
            return
        }
        listFromCatalog = true
        if (!keepList) _state.update { it.copy(list = ListState(loading = true, endReached = true)) }
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val cat = handle.state.value
            val items = withContext(compute) { localItems(cat.items, search) }
            if (gen != generation) return@launch
            if (items == null) {
                listFromCatalog = false
                startServerList(gen)
                return@launch
            }
            val downloading = cat.status is SyncStatus.Downloading
            val failed = (cat.status as? SyncStatus.Failed)?.message?.takeIf { items.isEmpty() && search == null }
            val askServer = search != null && keywordsOf(search).isNotBlank()
            _state.update {
                it.copy(
                    list = ListState(
                        items = items,
                        endReached = true,
                        loading = askServer || failed == null && (!cat.loaded || (downloading && search == null) ||
                            (items.isEmpty() && cat.status is SyncStatus.Checking)),
                        error = failed?.let { message -> "$message\n$OFFLINE_HINT" },
                    ),
                )
            }
            autoSelect()
            if (askServer) addServerMatches(gen, items, cat.items)
        }
    }

    /**
     * Keyword searches also match readings (romaji, kana) that only the server knows: ask it with the same
     * search and add the levels it finds that the saved list didn't. Offline, the local results stand.
     */
    private suspend fun addServerMatches(gen: Int, local: List<LevelItem>, saved: List<LevelItem>) {
        val api = source ?: return
        val params = currentParams()
        val found = try {
            val first = api.levelList(params, null)
            val more = if (first.cursor == null && first.pageCount > 1) {
                coroutineScope {
                    (1 until minOf(first.pageCount, SERVER_MATCH_PAGES)).map { page ->
                        async { runCatching { api.levelList(params + ("page" to "$page"), null).items }.getOrDefault(emptyList()) }
                    }.awaitAll().flatten()
                }
            } else {
                emptyList()
            }
            first.items + more
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (gen != generation) return
        val shown = local.mapTo(HashSet()) { it.name }
        val savedByName = saved.associateBy { it.name }
        val extra = found.filter { shown.add(it.name) }.map { savedByName[it.name] ?: it }
        _state.update { it.copy(list = it.list.copy(items = local + clusterSongs(extra), loading = false)) }
        autoSelect()
    }

    private fun keywordsOf(search: SearchState): String {
        val form = search.forms.getOrNull(search.activeForm) ?: return search.quickKeywords
        val index = SearchForms.keywordsIndex(form) ?: return ""
        return (search.values[search.activeForm].getOrNull(index) as? OptionState.Text)?.value.orEmpty()
    }

    /** The catalog's levels for [search] (all of them, songs together, when null); null when it can't be answered. */
    private fun localItems(items: List<LevelItem>, search: SearchState?): List<LevelItem>? {
        if (search == null) return clusterSongs(items)
        val form = search.forms.getOrNull(search.activeForm) ?: return clusterSongs(localSearch.quick(items, search.quickKeywords))
        val values = search.values[search.activeForm]
        val result = localSearch.run(items, form, values) ?: return null
        // Keep a song's charts together unless the user picked another order.
        val sort = SearchForms.sortOptions(form).indices
        val defaults = SearchForms.defaults(form)
        val reordered = sort.any { values[it] != defaults[it] } ||
            form.options.indices.any { form.options[it].query.lowercase() == "random" && values[it] == OptionState.Toggle(true) }
        return if (reordered) result else clusterSongs(result)
    }

    private fun startServerList(gen: Int) {
        _state.update { it.copy(list = ListState(loading = true)) }
        fetchPages(gen, currentParams(), cursor = null, pages = listOf(0), append = false)
    }

    private fun loadFirstPage() {
        debounceJob?.cancel()
        listJob?.cancel()
        val gen = ++generation
        val s = _state.value
        val section = s.activeSection
        if (s.search == null && section != null) {
            listFromCatalog = false
            val levels = s.sections.getOrNull(section)?.levels(ServerJson).orEmpty()
            _state.update { it.copy(list = ListState(items = levels, endReached = true)) }
            autoSelect()
            return
        }
        if (catalog != null) {
            showLocal(gen, keepList = false)
            return
        }
        startServerList(gen)
    }

    /** Fetches [pages] (one page when [cursor] is set) in parallel and adds them to the list in order. */
    private fun fetchPages(gen: Int, params: Map<String, String>, cursor: String?, pages: List<Int>, append: Boolean) {
        val api = source ?: return
        listJob = viewModelScope.launch {
            try {
                val results = coroutineScope {
                    pages.map { page ->
                        async {
                            val query = if (cursor == null && page > 0) params + ("page" to page.toString()) else params
                            api.levelList(query, cursor)
                        }
                    }.awaitAll()
                }
                if (gen != generation) return@launch
                val page = pages.last()
                val result = results.last()
                _state.update { s ->
                    val kept = if (append) s.list.items else emptyList()
                    val seen = kept.mapTo(HashSet()) { it.name }
                    // Servers can repeat a level across pages (e.g. new uploads shift page boundaries).
                    val fresh = results.flatMap { it.items }.filter { seen.add(it.name) }
                    s.copy(
                        list = ListState(
                            items = kept + fresh,
                            cursor = result.cursor,
                            endReached = result.cursor == null && result.pageCount <= page + 1,
                            page = page,
                            pageCount = result.pageCount,
                        ),
                    )
                }
                autoSelect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    _state.update { it.copy(list = it.list.copy(loading = false, error = errorText(e))) }
                }
            }
        }
    }

    private fun autoSelect() {
        val s = _state.value
        if (s.selected == null) s.list.items.firstOrNull()?.let { selectLevel(it) }
    }

    private fun selectLevel(level: LevelItem) {
        _state.update { it.copy(selected = level, details = Loadable.Loading) }
        detailsJob?.cancel()
        val api = source ?: return
        prefetchSiblings(api, level)
        detailsJob = viewModelScope.launch {
            try {
                val details = api.levelDetails(level.name)
                if (_state.value.selected?.name == level.name) {
                    _state.update { it.copy(details = Loadable.Ready(details)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.selected?.name == level.name) {
                    _state.update { it.copy(details = Loadable.Error(errorText(e))) }
                }
            }
        }
    }

    /**
     * Loads the details of [level]'s other charts in the list row (see [groupLevels]) in the background,
     * so switching difficulty is instant when the source caches. Failures are ignored.
     */
    private fun prefetchSiblings(api: LevelSource, level: LevelItem) {
        val group = groupLevels(_state.value.list.items).firstOrNull { g -> g.levels.any { it.name == level.name } } ?: return
        val siblings = group.levels.filter { it.name != level.name && it.name !in prefetched }
        prefetched += siblings.map { it.name }
        siblings.forEach { sibling ->
            viewModelScope.launch {
                try {
                    api.levelDetails(sibling.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    prefetched -= sibling.name
                }
            }
        }
    }


    private fun currentParams(): Map<String, String> {
        val search = _state.value.search ?: return emptyMap()
        if (search.activeForm < 0) {
            return if (search.quickKeywords.isBlank()) emptyMap() else SearchForms.quick(search.quickKeywords)
        }
        return SearchForms.query(search.forms[search.activeForm], search.values[search.activeForm])
    }

    private fun debounceReload() {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            debounceJob = null
            loadFirstPage()
        }
    }

    private fun updateSearch(transform: (SearchState) -> SearchState) {
        _state.update { s -> s.search?.let { s.copy(search = transform(it)) } ?: s }
    }

    private fun errorText(e: Exception): String = when (e) {
        is ServerApiException -> "The server returned an error (${e.code})"
        is SerializationException -> "The server sent data this app can't read"
        is IOException -> "Couldn't reach the server"
        else -> e.message ?: "Something went wrong"
    }

    companion object {
        const val OFFLINE_HINT = "Downloaded levels are under Play"
        const val INVALID_ADDRESS = "Enter a valid server address"
        private const val DEBOUNCE_MS = 400L

        /** How many numbered pages one "load more" fetches at once. */
        const val PARALLEL_PAGES = 3

        /** Server pages read for keyword matches the saved list lacks (romaji and kana readings). */
        private const val SERVER_MATCH_PAGES = 3
    }
}
