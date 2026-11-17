package my.fumui.sairitsu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.gameplay.ResultStore
import my.fumui.sairitsu.gameplay.RunHistory
import my.fumui.sairitsu.gameplay.RunRecord
import my.fumui.sairitsu.gameplay.RunSort
import my.fumui.sairitsu.gameplay.sortRuns
import my.fumui.sairitsu.library.CollectionStore
import my.fumui.sairitsu.library.LevelCollection
import my.fumui.sairitsu.library.LevelStats
import my.fumui.sairitsu.library.Library
import my.fumui.sairitsu.library.sortedByName
import my.fumui.sairitsu.library.LibraryEntry
import my.fumui.sairitsu.library.LibraryFacets
import my.fumui.sairitsu.library.LibraryQuery
import my.fumui.sairitsu.library.LibraryQueryStore
import my.fumui.sairitsu.library.applyQuery
import my.fumui.sairitsu.library.facets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val query: LibraryQuery = LibraryQuery(),
    val facets: LibraryFacets = facets(emptyList()),
    val visible: List<LibraryEntry> = emptyList(),
    val selected: LibraryEntry? = null,
    val details: Loadable<ItemDetails> = Loadable.Idle,
    val stats: Map<LevelKey, LevelStats> = emptyMap(),
    /** The selected level's Play runs, ordered by [runSort]. */
    val runs: List<RunRecord> = emptyList(),
    /** How [runs] are ordered; kept for the session only. */
    val runSort: RunSort = RunSort.Arcade,
    val showRecoveredMessage: Boolean = false,
    /** False until the saved query is read (the list would otherwise flash unfiltered). */
    val ready: Boolean = false,
    /** A message for the snackbar; cleared by [LibraryViewModel.messageShown]. */
    val message: String? = null,
    /** Every collection, sorted by name. */
    val collections: List<LevelCollection> = emptyList(),
    /** The collection this picker shows ([PickerScope.InCollection]); follows renames. */
    val collection: LevelCollection? = null,
    /** The [PickerScope.InCollection] collection doesn't exist (deleted): the screen should close. */
    val missing: Boolean = false,
    /** The scope has no downloaded levels at all (not just none matching the filters). */
    val scopeEmpty: Boolean = false,
    /** The levels picked in selection mode; non-empty means selection mode is on. */
    val selection: Set<LevelKey> = emptySet(),
    /**
     * The collection "Remove from collection" takes the selection out of: the open collection, or in Play the one
     * collection the filter is set to. Null when neither applies.
     */
    val removeTarget: LevelCollection? = null,
)

/** Which levels a picker shows: the whole library (Play) or one collection's members. */
sealed interface PickerScope {
    data object All : PickerScope
    data class InCollection(val id: String) : PickerScope
}

/**
 * The downloaded-levels picker behind Play and each collection screen: the levels in [scope], searched, filtered and
 * sorted by a [LibraryQuery]. Play loads and saves the query in [queryStore]; with no store (a collection screen) the
 * query starts clear every time and is never saved.
 */
class LibraryViewModel(
    private val library: Library,
    private val results: ResultStore,
    private val queryStore: LibraryQueryStore?,
    /** Every Play run per level; null: no run list. */
    private val history: RunHistory? = null,
    private val collections: CollectionStore? = null,
    private val scope: PickerScope = PickerScope.All,
    private val readDetails: suspend (LibraryEntry) -> ItemDetails?,
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState(showRecoveredMessage = library.index.recoveredFromCorruption))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private var detailsJob: Job? = null
    private var runsJob: Job? = null

    /** Each downloaded level's description (readings like romaji live there), read once for search. */
    private val searchText = HashMap<LevelKey, String>()
    private var searchTextJob: Job? = null

    /** Where the deleted entry was in the list, so the selection moves to its neighbour. */
    private var deletedAt: Int? = null

    init {
        if (collections?.recoveredFromCorruption == true) {
            collections.acknowledgeRecovery()
            _state.update { it.copy(message = COLLECTIONS_RECOVERED) }
        }
        viewModelScope.launch {
            val query = queryStore?.query?.first() ?: LibraryQuery()
            val stats = loadStats()
            _state.update { it.copy(query = query, stats = stats, ready = true) }
            applyCollections(collections?.collections?.value.orEmpty())
            library.index.entries.collect {
                recompute()
                loadSearchText()
            }
        }
        if (collections != null) viewModelScope.launch { collections.collections.collect(::applyCollections) }
    }

    fun setQuery(query: LibraryQuery) {
        _state.update { it.copy(query = query) }
        recompute()
        if (queryStore != null) viewModelScope.launch { queryStore.save(query) }
    }

    fun setRunSort(sort: RunSort) {
        _state.update { it.copy(runSort = sort, runs = sortRuns(it.runs, sort)) }
    }

    fun select(entry: LibraryEntry) {
        _state.update { it.copy(selected = entry, details = Loadable.Loading, runs = emptyList()) }
        loadRuns(entry.key)
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            val details = try {
                readDetails(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (_state.value.selected?.key == entry.key) {
                _state.update { it.copy(details = details?.let { d -> Loadable.Ready(d) } ?: Loadable.Idle) }
            }
        }
    }

    fun delete(entry: LibraryEntry) {
        deletedAt = _state.value.visible.indexOfFirst { it.key == entry.key }.takeIf { it >= 0 }
        viewModelScope.launch {
            try {
                library.delete(entry.key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // E.g. the disk is full and the index can't be rewritten: nothing was deleted.
                deletedAt = null
                _state.update { it.copy(message = DELETE_FAILED) }
                return@launch
            }
            // If this fails, the next load's prune drops the level from its collections.
            saveCollections { removeLevel(entry.key) }
        }
    }

    /** Starts selection mode with [keys] (a long-pressed row's charts, or one chart). */
    fun startSelection(keys: Collection<LevelKey>) = _state.update { it.copy(selection = keys.toSet()) }

    /** Deselects [keys] when all of them are selected, else selects them all. */
    fun toggleSelection(keys: Collection<LevelKey>) = _state.update { s ->
        s.copy(selection = if (s.selection.containsAll(keys)) s.selection - keys.toSet() else s.selection + keys)
    }

    /** Selects every level the list shows; when they're all selected already, clears the selection. */
    fun selectAll() = _state.update { s ->
        val shown = s.visible.mapTo(HashSet()) { it.key }
        s.copy(selection = if (s.selection.containsAll(shown)) emptySet() else shown)
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /** Adds [keys] to collection [id], or removes them from it. */
    fun setMembership(id: String, keys: Set<LevelKey>, add: Boolean) {
        viewModelScope.launch { saveCollections { if (add) add(setOf(id), keys) else remove(setOf(id), keys) } }
    }

    /** Creates a collection named [name] holding [keys] (the caller validated the name). */
    fun createCollection(name: String, keys: Set<LevelKey>) {
        viewModelScope.launch { saveCollections { create(name, keys) } }
    }

    /** Takes the selection out of [LibraryUiState.removeTarget] and ends selection mode. */
    fun removeSelectionFromTarget() {
        val s = _state.value
        val target = s.removeTarget ?: return
        val keys = s.selection
        clearSelection()
        viewModelScope.launch { saveCollections { remove(setOf(target.id), keys) } }
    }

    /** Runs [change] on the collection store, turning a failed write into a snackbar message. */
    private suspend fun saveCollections(change: suspend CollectionStore.() -> Unit) {
        val store = collections ?: return
        try {
            store.change()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = COLLECTIONS_SAVE_FAILED) }
        }
    }

    /** Takes a new collection list: the scoped collection, and (Play) the query without ids that no longer exist. */
    private fun applyCollections(list: List<LevelCollection>) {
        val scoped = (scope as? PickerScope.InCollection)?.let { s -> list.firstOrNull { it.id == s.id } }
        _state.update {
            it.copy(collections = list.sortedByName(), collection = scoped, missing = scope is PickerScope.InCollection && scoped == null)
        }
        val s = _state.value
        if (!s.ready) return
        if (scope == PickerScope.All && collections != null) {
            val ids = list.mapTo(HashSet()) { it.id }
            val kept = s.query.collections.filterTo(LinkedHashSet()) { it in ids }
            if (kept != s.query.collections) {
                setQuery(s.query.copy(collections = kept))
                return
            }
        }
        recompute()
    }

    fun recoveredMessageShown() {
        library.index.acknowledgeRecovery()
        _state.update { it.copy(showRecoveredMessage = false) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Re-reads best results and last played (after a Play returns to this screen). */
    fun refreshStats() {
        viewModelScope.launch {
            val stats = loadStats()
            _state.update { it.copy(stats = stats) }
            recompute()
            _state.value.selected?.let { loadRuns(it.key) }
        }
    }

    /** Reads [key]'s runs (else its stored best, undated); dropped when the selection moved on meanwhile. */
    private fun loadRuns(key: LevelKey) {
        runsJob?.cancel()
        runsJob = viewModelScope.launch {
            val runs = try {
                history?.runs(key) ?: listOfNotNull(_state.value.stats[key]?.best?.let { RunRecord(null, it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (_state.value.selected?.key == key) {
                _state.update { it.copy(runs = sortRuns(runs, it.runSort)) }
            }
        }
    }

    private suspend fun loadStats(): Map<LevelKey, LevelStats> = try {
        results.stats()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }

    /** Reads the descriptions of entries not read yet, then searches again with them. */
    private fun loadSearchText() {
        if (searchTextJob?.isActive == true) return
        val missing = library.index.entries.value.filter { it.key !in searchText }
        if (missing.isEmpty()) return
        searchTextJob = viewModelScope.launch {
            missing.forEach { entry ->
                val details = try {
                    readDetails(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                searchText[entry.key] = details?.description.orEmpty()
            }
            if (_state.value.query.text.isNotBlank()) recompute()
            // Entries added while reading are picked up on the next pass.
            if (library.index.entries.value.any { it.key !in searchText }) loadSearchText()
        }
    }

    private fun recompute() {
        val s = _state.value
        if (!s.ready) return
        val all = library.index.entries.value
        val scoped = when (scope) {
            PickerScope.All -> all
            is PickerScope.InCollection -> s.collection?.levels?.toHashSet()?.let { m -> all.filter { it.key in m } }.orEmpty()
        }
        val members = s.collections.associate { it.id to it.levels.toHashSet() as Set<LevelKey> }
        val visible = applyQuery(scoped, s.stats, s.query, searchText, members)
        val scopedKeys = scoped.mapTo(HashSet()) { it.key }
        val removeTarget = when (scope) {
            is PickerScope.InCollection -> s.collection
            PickerScope.All -> s.query.collections.singleOrNull()?.let { id -> s.collections.firstOrNull { it.id == id } }
        }
        _state.update {
            it.copy(
                visible = visible,
                facets = facets(scoped),
                scopeEmpty = scoped.isEmpty(),
                // Selection follows the scope, not the filters (they're hidden while selecting).
                selection = it.selection.filterTo(HashSet()) { k -> k in scopedKeys },
                removeTarget = removeTarget,
            )
        }
        val selected = s.selected?.key
        val stillThere = visible.firstOrNull { it.key == selected }
        val removedAt = deletedAt
        deletedAt = null
        when {
            stillThere != null -> if (stillThere != s.selected) _state.update { it.copy(selected = stillThere) }
            visible.isEmpty() -> _state.update { it.copy(selected = null, details = Loadable.Idle) }
            removedAt != null -> select(visible[removedAt.coerceAtMost(visible.size - 1)])
            else -> select(visible.first())
        }
    }

    companion object {
        const val DELETE_FAILED = "Couldn't delete the level"
        const val COLLECTIONS_SAVE_FAILED = "Couldn't save collections"
        const val COLLECTIONS_RECOVERED = "The collections list was damaged and has been reset"
    }
}
