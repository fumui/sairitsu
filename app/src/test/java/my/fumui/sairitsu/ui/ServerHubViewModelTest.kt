package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.model.ServerInfo
import my.fumui.sairitsu.data.model.ServerInfoButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ServerHubViewModelTest {
    private val source = FakeLevelSource()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsInfo() = runTest {
        val info = ServerInfo("T", buttons = listOf(ServerInfoButton("level")))
        source.server = { info }
        val vm = ServerHubViewModel(source)
        advanceUntilIdle()
        assertEquals(Loadable.Ready(info), vm.info.value)
    }

    @Test
    fun failureIsErrorAndRetryReloads() = runTest {
        source.server = { throw IOException("down") }
        val vm = ServerHubViewModel(source)
        advanceUntilIdle()
        assertTrue(vm.info.value is Loadable.Error)

        val info = ServerInfo("T")
        source.server = { info }
        vm.retry()
        advanceUntilIdle()
        assertEquals(Loadable.Ready(info), vm.info.value)
    }
}
