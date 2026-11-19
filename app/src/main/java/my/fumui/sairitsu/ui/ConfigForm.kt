package my.fumui.sairitsu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.data.LevelSource
import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SearchForms
import my.fumui.sairitsu.data.ServerConfigCodec
import my.fumui.sairitsu.data.ServerApiException
import my.fumui.sairitsu.data.model.SearchOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException

/** The editable state of one server's configuration. Immutable; each edit returns a copy. */
data class ConfigForm(val options: List<SearchOption>, val values: List<OptionState>, val saved: List<OptionState>) {
    val changed: Boolean get() = values != saved

    fun set(index: Int, value: OptionState): ConfigForm =
        copy(values = values.mapIndexed { i, old -> if (i == index) value else old })

    /** Every value to its default; [saved] is unchanged, so the form counts as changed until saved. */
    fun reset(): ConfigForm = copy(values = options.map(SearchForms::defaultOf))

    fun params(): Map<String, String> = ServerConfigCodec.encode(options, values)

    companion object {
        fun of(options: List<SearchOption>, stored: Map<String, String>): ConfigForm {
            val decoded = ServerConfigCodec.decode(options, stored)
            return ConfigForm(options, decoded, decoded)
        }
    }
}

/** Loads one server's configuration options and the stored values, and holds the edits. */
class ServerConfigViewModel(
    private val source: LevelSource,
    private val stored: () -> Map<String, String>,
) : ViewModel() {
    private val _form = MutableStateFlow<Loadable<ConfigForm>>(Loadable.Loading)
    val form: StateFlow<Loadable<ConfigForm>> = _form.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun set(index: Int, value: OptionState) = edit { it.set(index, value) }

    fun reset() = edit { it.reset() }

    fun retry() = load()

    private fun edit(change: (ConfigForm) -> ConfigForm) = _form.update { state ->
        if (state is Loadable.Ready) Loadable.Ready(change(state.value)) else state
    }

    private fun load() {
        job?.cancel()
        _form.value = Loadable.Loading
        job = viewModelScope.launch {
            _form.value = try {
                val info = source.serverInfo()
                Loadable.Ready(ConfigForm.of(info.configuration?.options.orEmpty(), stored()))
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
