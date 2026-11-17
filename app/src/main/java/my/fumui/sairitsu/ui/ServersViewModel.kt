package my.fumui.sairitsu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.data.CatalogSummary
import my.fumui.sairitsu.data.LevelSource
import my.fumui.sairitsu.data.ServerCatalogs
import my.fumui.sairitsu.data.SavedServer
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.ServerListStore
import my.fumui.sairitsu.text.Localizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** What a server card shows beyond its saved title: the server's description and banner (absolute URL). */
data class ServerCard(val description: String?, val bannerUrl: String?)

/** The Online Servers list: add (titled from the server's info), edit and remove. */
class ServersViewModel(
    private val store: ServerListStore,
    private val apiFactory: (ServerAddress) -> LevelSource,
    private val catalogs: ServerCatalogs? = null,
) : ViewModel() {
    /** Each server's saved level list (count, last sync, sync progress), by address. */
    val catalogSummaries: StateFlow<Map<String, CatalogSummary>> = catalogs?.summaries ?: MutableStateFlow(emptyMap())

    val servers: StateFlow<List<SavedServer>> = store.servers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow<String?>(null)

    /** A message for the snackbar; cleared by [messageShown]. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _cards = MutableStateFlow<Map<String, ServerCard>>(emptyMap())

    /** Each reachable server's card info, by address; fetched once per address while the screen is open. */
    val cards: StateFlow<Map<String, ServerCard>> = _cards.asStateFlow()
    private val requested = HashSet<String>()

    private val localizer = Localizer(Localizer.serverLanguage(Locale.getDefault()))

    init {
        viewModelScope.launch {
            servers.collect { list ->
                list.forEach(::loadCard)
                catalogs?.loadSummaries(list.map { it.address })
            }
        }
    }

    private fun loadCard(server: SavedServer) {
        if (!requested.add(server.address)) return
        val address = ServerAddress.parse(server.url) ?: return
        viewModelScope.launch {
            val info = try {
                apiFactory(address).serverInfo()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                requested.remove(server.address)
                return@launch
            }
            val card = ServerCard(
                description = info.description?.let(localizer::text)?.takeIf { it.isNotBlank() },
                bannerUrl = address.resolve(info.banner?.url),
            )
            _cards.update { it + (server.address to card) }
        }
    }

    fun add(input: String) {
        val address = ServerAddress.parse(input)
        if (address == null) {
            _message.value = PickerViewModel.INVALID_ADDRESS
            return
        }
        viewModelScope.launch {
            if (servers.value.any { it.address == address.linkAddress }) {
                _message.value = DUPLICATE
                return@launch
            }
            val title = fetchTitle(address)
            when (store.add(input, title)) {
                ServerListStore.AddResult.Duplicate -> _message.value = DUPLICATE
                // Start saving the server's level list right away, in the background.
                is ServerListStore.AddResult.Added -> catalogs?.open(address)
                ServerListStore.AddResult.Invalid -> Unit
            }
        }
    }

    /** Changes [server]'s address to [input] and its title to [title]; a blank title asks the server for one. */
    fun edit(server: SavedServer, input: String, title: String) {
        val address = ServerAddress.parse(input)
        if (address == null) {
            _message.value = PickerViewModel.INVALID_ADDRESS
            return
        }
        viewModelScope.launch {
            val moved = address.linkAddress != server.address
            val newTitle = title.trim().ifBlank { fetchTitle(address).orEmpty() }
            when (store.update(server.address, input, newTitle)) {
                ServerListStore.AddResult.Duplicate -> _message.value = DUPLICATE
                ServerListStore.AddResult.Invalid -> _message.value = PickerViewModel.INVALID_ADDRESS
                is ServerListStore.AddResult.Added -> if (moved) {
                    requested.remove(server.address)
                    _cards.update { it - server.address }
                    catalogs?.delete(server.address)
                    catalogs?.open(address)
                }
            }
        }
    }

    private suspend fun fetchTitle(address: ServerAddress): String? = try {
        localizer.text(apiFactory(address).serverInfo().title)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        _message.value = UNREACHABLE
        null
    }

    fun remove(address: String) {
        viewModelScope.launch { store.remove(address) }
        catalogs?.delete(address)
    }

    /** Downloads [server]'s whole level list again (picks up edited and removed levels). */
    fun refreshLevels(server: SavedServer) {
        ServerAddress.parse(server.url)?.let { catalogs?.refresh(it) }
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        const val DUPLICATE = "This server is already in the list"
        const val UNREACHABLE = "Couldn't reach the server"
    }
}
