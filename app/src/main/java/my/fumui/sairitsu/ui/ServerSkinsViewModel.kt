package my.fumui.sairitsu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.data.ItemApi
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemSummary
import my.fumui.sairitsu.gameplay.SkinLibrary
import my.fumui.sairitsu.gameplay.sha1Hex
import my.fumui.sairitsu.library.downloadErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SkinRowState { NotSaved, Saving, Saved }

/** A server's skins page by page, each saved to the [SkinLibrary] with one tap. */
class ServerSkinsViewModel(
    private val server: ServerAddress,
    api: ItemApi,
    private val library: SkinLibrary,
) : ViewModel() {
    val pager = ItemPager(viewModelScope) { api.list("skins", it) }
    private val saving = MutableStateFlow<Set<String>>(emptySet())
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Save failures, for the snackbar. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    val rows: StateFlow<List<Pair<ItemSummary, SkinRowState>>> =
        combine(pager.state, library.skins, saving) { paged, saved, busy ->
            val ids = saved.map { it.id }.toSet()
            paged.items.mapNotNull { it.toSummary() }.map { item ->
                item to when {
                    sha1Hex("${server.linkAddress}|${item.name}") in ids -> SkinRowState.Saved
                    item.name in busy -> SkinRowState.Saving
                    else -> SkinRowState.NotSaved
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        pager.loadMore()
    }

    fun save(item: ItemSummary) {
        if (item.name in saving.value) return
        val raw = pager.state.value.items.firstOrNull { it.toSummary()?.name == item.name }
        val offer = raw?.let { library.offer(server, it) }
        if (offer == null) {
            _errors.tryEmit("This skin can't be saved")
            return
        }
        saving.value += item.name
        viewModelScope.launch {
            try {
                library.add(server, offer)
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
