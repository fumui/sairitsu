package my.fumui.sairitsu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.data.LevelSource
import my.fumui.sairitsu.data.ServerApiException
import my.fumui.sairitsu.data.model.ServerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException

/** Loads one server's info for its hub screen. */
class ServerHubViewModel(private val source: LevelSource) : ViewModel() {
    private val _info = MutableStateFlow<Loadable<ServerInfo>>(Loadable.Loading)
    val info: StateFlow<Loadable<ServerInfo>> = _info.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        job?.cancel()
        _info.value = Loadable.Loading
        job = viewModelScope.launch {
            _info.value = try {
                Loadable.Ready(source.serverInfo())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Error(errorText(e))
            }
        }
    }

    private fun errorText(e: Exception): String = when (e) {
        is ServerApiException -> "The server returned an error (${e.code})"
        is SerializationException -> "The server sent data this app can't read"
        is IOException -> "Couldn't reach the server"
        else -> e.message ?: "Something went wrong"
    }
}
