package my.fumui.sairitsu.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerListTest {
    /** DataStore's file store cannot rename over an open file on Windows, so the test keeps preferences in memory. */
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun startsWithNoServer() = runBlocking {
        assertEquals(emptyList<SavedServer>(), ServerListStore(MemoryStore()).servers.first())
    }

    @Test
    fun addedServersPersist() = runBlocking {
        val mem = MemoryStore()
        ServerListStore(mem).add("example.com", null)
        assertEquals(listOf(SavedServer("example.com", "example.com")), ServerListStore(mem).servers.first())
    }

    @Test
    fun addRejectsDuplicateByLinkAddress() = runBlocking {
        val store = ServerListStore(MemoryStore())
        store.add("example.com", null)
        assertEquals(ServerListStore.AddResult.Duplicate, store.add("http://example.com", "x"))
        assertEquals(ServerListStore.AddResult.Duplicate, store.add("https://example.com/", null))
        assertEquals(1, store.servers.first().size)
    }

    @Test
    fun addInvalidAddress() = runBlocking {
        assertEquals(ServerListStore.AddResult.Invalid, ServerListStore(MemoryStore()).add("nope", null))
    }

    @Test
    fun addKeepsOrder() = runBlocking {
        val store = ServerListStore(MemoryStore())
        val added = store.add("coconut.sonolus.com/horizon", "Horizon")
        assertEquals(ServerListStore.AddResult.Added(SavedServer("coconut.sonolus.com/horizon", "Horizon")), added)
        store.add("example.com", null)
        assertEquals(listOf("coconut.sonolus.com/horizon", "example.com"), store.servers.first().map { it.address })
        assertEquals("example.com", store.servers.first().last().title)
    }

    @Test
    fun keepsHttpScheme() = runBlocking {
        val store = ServerListStore(MemoryStore())
        store.add("example.com", null)
        store.add("http://192.168.1.5:3000", null)
        val saved = store.servers.first().last()
        assertEquals("192.168.1.5:3000", saved.address)
        assertEquals("http://192.168.1.5:3000", saved.url)
        assertEquals("https://example.com", store.servers.first().first().url)
    }

    @Test
    fun entryWithoutUrlFallsBackToHttps() {
        assertEquals("https://example.com", SavedServer("example.com", "t").url)
    }

    @Test
    fun removeAndSetTitle() = runBlocking {
        val store = ServerListStore(MemoryStore())
        store.add("example.com", null)
        store.add("example.org", null)
        store.setTitle("example.com", "Example")
        store.remove("example.org")
        assertEquals(listOf(SavedServer("example.com", "Example")), store.servers.first())
    }
}
