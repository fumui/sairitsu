package my.fumui.sairitsu.ui

import my.fumui.sairitsu.MemoryDataStore
import my.fumui.sairitsu.data.SavedServer
import my.fumui.sairitsu.data.ServerListStore
import my.fumui.sairitsu.data.model.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ServerListStore(MemoryDataStore())
    private var info: suspend () -> ServerInfo = { ServerInfo(title = "Coconut Horizon") }
    private val source = object : my.fumui.sairitsu.data.LevelSource by FakeLevelSource() {
        override suspend fun serverInfo(): ServerInfo = info()
    }

    private class FakeCatalogs : my.fumui.sairitsu.data.ServerCatalogs {
        val loaded = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val refreshed = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        override val summaries = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, my.fumui.sairitsu.data.CatalogSummary>())

        override fun loadSummaries(servers: List<String>) {
            servers.forEach { if (it !in loaded) loaded += it }
        }

        override fun open(server: my.fumui.sairitsu.data.ServerAddress) {
            opened += server.linkAddress
        }

        override fun refresh(server: my.fumui.sairitsu.data.ServerAddress) {
            refreshed += server.linkAddress
        }

        override fun delete(server: String) {
            deleted += server
        }
    }

    private val catalogs = FakeCatalogs()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        runBlocking { store.add("sonolus.sbuga.com", null) }  // the app starts with none; most cases need one
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.vm(): ServersViewModel = ServersViewModel(store, { source }, catalogs).also { advanceUntilIdle() }

    @Test
    fun listsSavedServers() = test {
        assertEquals(listOf("sonolus.sbuga.com"), vm().servers.value.map { it.address })
    }

    @Test
    fun addValid() = test {
        val vm = vm()
        vm.add("https://coconut.sonolus.com/horizon/")
        advanceUntilIdle()
        assertEquals(SavedServer("coconut.sonolus.com/horizon", "Coconut Horizon"), store.servers.first().last())
        assertNull(vm.message.value)
    }

    @Test
    fun addDuplicateMessage() = test {
        val vm = vm()
        vm.add("http://sonolus.sbuga.com")
        advanceUntilIdle()
        assertEquals("This server is already in the list", vm.message.value)
        assertEquals(1, store.servers.first().size)
    }

    @Test
    fun addUnreachableStillAdds() = test {
        info = { throw IOException("offline") }
        val vm = vm()
        vm.add("example.com")
        advanceUntilIdle()
        assertEquals(SavedServer("example.com", "example.com"), store.servers.first().last())
        assertEquals("Couldn't reach the server", vm.message.value)
        vm.messageShown()
        assertNull(vm.message.value)
    }

    @Test
    fun addInvalidMessage() = test {
        val vm = vm()
        vm.add("nope")
        advanceUntilIdle()
        assertEquals("Enter a valid server address", vm.message.value)
        assertEquals(1, store.servers.first().size)
    }

    @Test
    fun loadsCards() = test {
        info = { ServerInfo(title = "Sbuga", description = "Hello", banner = my.fumui.sairitsu.data.model.Srl(url = "/b.png")) }
        val vm = vm()
        assertEquals(ServerCard("Hello", "https://sonolus.sbuga.com/b.png"), vm.cards.value["sonolus.sbuga.com"])
    }

    @Test
    fun editKeepsPositionAndRenames() = test {
        val vm = vm()
        vm.add("example.com")
        advanceUntilIdle()
        vm.edit(store.servers.first().first(), "sonolus.sbuga.com", "My Sbuga")
        advanceUntilIdle()
        assertEquals(listOf("My Sbuga", "Coconut Horizon"), store.servers.first().map { it.title })
    }

    @Test
    fun editToOtherListedServerIsDuplicate() = test {
        val vm = vm()
        vm.add("example.com")
        advanceUntilIdle()
        vm.edit(store.servers.first().first(), "example.com", "x")
        advanceUntilIdle()
        assertEquals("This server is already in the list", vm.message.value)
        assertEquals(listOf("sonolus.sbuga.com", "example.com"), store.servers.first().map { it.address })
    }

    @Test
    fun addStartsSavingLevelsAndRemoveForgetsThem() = test {
        val vm = vm()
        assertEquals(listOf("sonolus.sbuga.com"), catalogs.loaded)
        vm.add("example.com")
        advanceUntilIdle()
        assertEquals(listOf("example.com"), catalogs.opened)
        vm.refreshLevels(store.servers.first().last())
        assertEquals(listOf("example.com"), catalogs.refreshed)
        vm.remove("example.com")
        advanceUntilIdle()
        assertEquals(listOf("example.com"), catalogs.deleted)
    }

    @Test
    fun movingAServerReplacesItsSavedLevels() = test {
        val vm = vm()
        vm.edit(store.servers.first().first(), "example.com", "Example")
        advanceUntilIdle()
        assertEquals(listOf("sonolus.sbuga.com"), catalogs.deleted)
        assertEquals(listOf("example.com"), catalogs.opened)
    }

    @Test
    fun remove() = test {
        val vm = vm()
        vm.remove("sonolus.sbuga.com")
        advanceUntilIdle()
        assertEquals(emptyList<SavedServer>(), vm.servers.value)
    }
}
