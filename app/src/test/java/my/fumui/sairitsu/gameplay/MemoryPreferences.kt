package my.fumui.sairitsu.gameplay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/** DataStore's file store cannot rename over an open file on Windows, so the tests keep preferences in memory. */
internal class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    /** Writes wait for this while it is set. */
    var writeGate: CompletableDeferred<Unit>? = null
    var failWrites = false

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        writeGate?.await()
        if (failWrites) throw IOException("disk full")
        return transform(state.value).also { state.value = it }
    }
}
