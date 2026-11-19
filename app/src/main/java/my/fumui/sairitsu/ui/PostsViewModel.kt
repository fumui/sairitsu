package my.fumui.sairitsu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.data.ItemApi
import my.fumui.sairitsu.data.model.ItemDetailsText
import my.fumui.sairitsu.data.model.ItemSummary
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

/** A post open in the reader: its list row and the [description] from its details. */
data class PostView(val summary: ItemSummary, val description: String?)

/** A server's posts page by page, and the details of the one selected. */
class PostsViewModel(private val api: ItemApi) : ViewModel() {
    val pager = ItemPager(viewModelScope) { api.list("posts", it) }
    val posts: StateFlow<List<ItemSummary>> =
        pager.state.map { paged -> paged.items.mapNotNull { it.toSummary() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()
    private val _details = MutableStateFlow<Loadable<PostView>>(Loadable.Idle)
    val details: StateFlow<Loadable<PostView>> = _details.asStateFlow()
    private var job: Job? = null

    init {
        pager.loadMore()
    }

    /** Opens [name], dropping a load still pending for the previous post. */
    fun select(name: String) {
        _selected.value = name
        loadDetails(name)
    }

    fun retryDetails() {
        _selected.value?.let(::loadDetails)
    }

    private fun loadDetails(name: String) {
        job?.cancel()
        _details.value = Loadable.Loading
        job = viewModelScope.launch {
            _details.value = try {
                val response = api.details("posts", name)
                val summary = posts.value.firstOrNull { it.name == name }
                    ?: (response["item"] as? JsonObject)?.toSummary()
                    ?: ItemSummary(name)
                Loadable.Ready(PostView(summary, description(response)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Error(downloadErrorText(e))
            }
        }
    }

    private fun description(response: JsonObject): String? = try {
        ServerJson.decodeFromJsonElement(ItemDetailsText.serializer(), response).description
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
