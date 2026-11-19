package my.fumui.sairitsu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.data.ItemApi
import my.fumui.sairitsu.data.model.PlaylistSummary
import my.fumui.sairitsu.data.model.ServerJson
import my.fumui.sairitsu.library.downloadErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** The playlist in a list item or a details response; null when it doesn't decode. */
internal fun JsonObject.toPlaylist(): PlaylistSummary? = try {
    ServerJson.decodeFromJsonElement(PlaylistSummary.serializer(), this)
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/** A playlist's full details (`sonolus/playlists/{name}`): its `item`, with every level once. */
internal suspend fun ItemApi.playlist(name: String): PlaylistSummary {
    val playlist = (details("playlists", name)["item"] as? JsonObject)?.toPlaylist() ?: throw IOException("Unreadable playlist $name")
    // A level listed twice would repeat a list key (a crash) and inflate the dialog's counts.
    return playlist.copy(levels = playlist.levels.distinctBy { it.name })
}

/** "Download all as collection" asked for [name]; [playlist] is its details as they load. */
data class SaveRequest(val name: String, val playlist: Loadable<PlaylistSummary>)

/** A server's playlists, page by page. */
class PlaylistsViewModel(private val api: ItemApi) : ViewModel() {
    val pager = ItemPager(viewModelScope) { api.list("playlists", it) }
    val playlists: StateFlow<List<PlaylistSummary>> =
        pager.state.map { paged -> paged.items.mapNotNull { it.toPlaylist() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _saveRequest = MutableStateFlow<SaveRequest?>(null)

    /** The open "Download all as collection" dialog: one at a time, the latest request wins. */
    val saveRequest: StateFlow<SaveRequest?> = _saveRequest.asStateFlow()
    private var saveJob: Job? = null

    init {
        pager.loadMore()
    }

    /** Opens the dialog for [name] at once and loads its full levels; replaces any earlier request. */
    fun requestSave(name: String) {
        saveJob?.cancel()
        _saveRequest.value = SaveRequest(name, Loadable.Loading)
        saveJob = viewModelScope.launch {
            val loaded = details(name).fold({ Loadable.Ready(it) }, { Loadable.Error(downloadErrorText(it)) })
            if (_saveRequest.value?.name == name) _saveRequest.value = SaveRequest(name, loaded)
        }
    }

    fun dismissSave() {
        saveJob?.cancel()
        _saveRequest.value = null
    }

    /** The playlist's full levels from its details. */
    suspend fun details(name: String): Result<PlaylistSummary> = try {
        Result.success(api.playlist(name))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** One playlist with all its levels. */
class PlaylistViewModel(private val api: ItemApi, private val name: String) : ViewModel() {
    private val _playlist = MutableStateFlow<Loadable<PlaylistSummary>>(Loadable.Loading)
    val playlist: StateFlow<Loadable<PlaylistSummary>> = _playlist.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        job?.cancel()
        _playlist.value = Loadable.Loading
        job = viewModelScope.launch {
            _playlist.value = try {
                Loadable.Ready(api.playlist(name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Error(downloadErrorText(e))
            }
        }
    }
}
