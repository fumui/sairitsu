package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.ItemPage
import my.fumui.sairitsu.data.PageToken
import my.fumui.sairitsu.data.model.ItemSummary
import my.fumui.sairitsu.data.model.ServerJson
import my.fumui.sairitsu.library.downloadErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The rows loaded so far; [end] once the server has no more pages. */
data class PagedItems(
    val items: List<JsonObject> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val end: Boolean = false,
)

/**
 * Pages through a server's list, one page per [loadMore] call; the caller (the scrolling list) decides when.
 * After an error nothing loads until [retry].
 */
class ItemPager(private val scope: CoroutineScope, private val load: suspend (PageToken?) -> ItemPage) {
    private val _state = MutableStateFlow(PagedItems())
    val state: StateFlow<PagedItems> = _state.asStateFlow()
    private var next: PageToken? = null

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.end || current.error != null) return
        _state.value = current.copy(loading = true)
        val token = next
        scope.launch {
            try {
                val page = load(token)
                next = page.next
                val seen = _state.value.items.mapNotNullTo(HashSet()) { it.nameOrNull() }
                val fresh = page.items.filter { item -> item.nameOrNull()?.let(seen::add) == true }
                _state.value = _state.value.copy(items = _state.value.items + fresh, loading = false, end = page.next == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = downloadErrorText(e))
            }
        }
    }

    fun retry() {
        if (_state.value.error == null) return
        _state.value = _state.value.copy(error = null)
        loadMore()
    }
}

private fun JsonObject.nameOrNull(): String? = (this["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The row as a summary; null when it doesn't decode. */
internal fun JsonObject.toSummary(): ItemSummary? = try {
    ServerJson.decodeFromJsonElement(ItemSummary.serializer(), this)
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}
