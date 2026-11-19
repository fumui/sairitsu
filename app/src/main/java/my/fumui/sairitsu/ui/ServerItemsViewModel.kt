package my.fumui.sairitsu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.data.ItemApi
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemSummary
import my.fumui.sairitsu.gameplay.ItemKind
import my.fumui.sairitsu.gameplay.ItemLibrary
import my.fumui.sairitsu.gameplay.ItemOffer
import my.fumui.sairitsu.gameplay.ItemPreview
import my.fumui.sairitsu.library.downloadErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ItemRowState { NotSaved, Saving, Saved }

/** A server's items of one [kind] page by page, each saved to the [ItemLibrary] with one tap. */
class ServerItemsViewModel(
    private val server: ServerAddress,
    val kind: ItemKind,
    api: ItemApi,
    private val library: ItemLibrary,
) : ViewModel() {
    val pager = ItemPager(viewModelScope) { api.list(kind.listType, it) }
    private val saving = MutableStateFlow<Set<String>>(emptySet())
    private val _previewing = MutableStateFlow<Set<String>>(emptySet())
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private var previewJob: Job? = null

    /** Save failures, for the snackbar. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    val rows: StateFlow<List<Pair<ItemSummary, ItemRowState>>> =
        combine(pager.state, library.items, saving) { paged, saved, busy ->
            val ids = saved.map { it.id }.toSet()
            paged.items.mapNotNull { it.toSummary() }.map { item ->
                item to when {
                    ItemLibrary.idOf(kind, server.linkAddress, item.name) in ids -> ItemRowState.Saved
                    item.name in busy -> ItemRowState.Saving
                    else -> ItemRowState.NotSaved
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        pager.loadMore()
    }

    /** Names of the items downloading for a preview. */
    val previewing: StateFlow<Set<String>> = _previewing.asStateFlow()

    private fun offerOf(item: ItemSummary): ItemOffer? {
        val raw = pager.state.value.items.firstOrNull { it.toSummary()?.name == item.name }
        val offer = raw?.let { library.offer(kind, server, it) }
        if (offer == null) _errors.tryEmit("This ${kind.label} can't be saved")
        return offer
    }

    /** Downloads [item] as a temporary entry, then calls [onReady] on the main thread; failures go to [errors]. */
    fun preview(item: ItemSummary, onReady: (ItemPreview) -> Unit) {
        if (item.name in _previewing.value) return
        val offer = offerOf(item) ?: return
        _previewing.value += item.name
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                val temporary = library.preview(server, offer)
                onReady(ItemPreview(kind, temporary.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.emit(downloadErrorText(e))
            } finally {
                _previewing.value -= item.name
            }
        }
    }

    /** Saves [item]; a temporary entry from an earlier preview is promoted without downloading again. */
    fun save(item: ItemSummary) {
        if (item.name in saving.value) return
        val id = ItemLibrary.idOf(kind, server.linkAddress, item.name)
        saving.value += item.name
        viewModelScope.launch {
            try {
                if (library.promote(id) != null) return@launch
                library.add(server, offerOf(item) ?: return@launch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.emit(downloadErrorText(e))
            } finally {
                saving.value -= item.name
            }
        }
    }
}
