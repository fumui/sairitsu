package my.fumui.sairitsu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.library.CollectionCard
import my.fumui.sairitsu.library.CollectionStore
import my.fumui.sairitsu.library.LevelCollection
import my.fumui.sairitsu.library.LibraryEntry
import my.fumui.sairitsu.library.LibraryQueryStore
import my.fumui.sairitsu.library.collectionCards
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionsUiState(
    val cards: List<CollectionCard> = emptyList(),
    val collections: List<LevelCollection> = emptyList(),
    /** A message for the snackbar; cleared by [CollectionsViewModel.messageShown]. */
    val message: String? = null,
)

/** The Collections grid: a card per collection, and create, rename and delete. */
class CollectionsViewModel(
    private val store: CollectionStore,
    entries: StateFlow<List<LibraryEntry>>,
    /** Play's saved query, which forgets a deleted collection. */
    private val queryStore: LibraryQueryStore,
) : ViewModel() {
    private val _state = MutableStateFlow(CollectionsUiState())
    val state: StateFlow<CollectionsUiState> = _state.asStateFlow()

    init {
        if (store.recoveredFromCorruption) {
            store.acknowledgeRecovery()
            _state.update { it.copy(message = LibraryViewModel.COLLECTIONS_RECOVERED) }
        }
        viewModelScope.launch {
            combine(store.collections, entries) { collections, library -> collections to collectionCards(collections, library) }
                .collect { (collections, cards) -> _state.update { it.copy(collections = collections, cards = cards) } }
        }
    }

    fun create(name: String) = save { store.create(name) }

    fun rename(id: String, name: String) = save { store.rename(id, name) }

    /** Deletes the collection (never its levels) and drops it from Play's saved filter. */
    fun delete(id: String) = save {
        store.delete(id)
        val query = queryStore.query.first()
        if (id in query.collections) queryStore.save(query.copy(collections = query.collections - id))
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = LibraryViewModel.COLLECTIONS_SAVE_FAILED) }
            }
        }
    }
}
