package my.fumui.sairitsu.gameplay

import android.graphics.SurfaceTexture
import android.view.Surface
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.ServerApiException
import my.fumui.sairitsu.data.model.LevelItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class GameplayViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val server = ServerAddress.parse("sonolus.sbuga.com")!!
    private val resources = LoadedResources(ResourceKind.entries.associateWith { File(it.wireName) })

    private val fetches = mutableListOf<String>()
    private var fetch: suspend ((LoadProgress) -> Unit) -> LoadedResources = { resources }
    private val fetcher = LevelFetcher { _, level, onProgress ->
        fetches += level
        fetch(onProgress)
    }
    private val runtime = FakeRuntime()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.started(): GameplayViewModel {
        val vm = GameplayViewModel(server, "sss-722-1279-expert", fetcher, runtime, dispatcher)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun optionsScreenReadsOnlyTheEngineConfiguration() = test {
        val config = File.createTempFile("engine", ".json").apply {
            deleteOnExit()
            writeBytes(GameplayViewModelTest::class.java.getResourceAsStream("/engine-configuration-test.json")!!.readBytes())
        }
        val configs = mutableListOf<String>()
        val configFetcher = object : LevelFetcher {
            override suspend fun load(server: ServerAddress, level: String, onProgress: (LoadProgress) -> Unit) =
                fetcher.load(server, level, onProgress)

            override suspend fun engineConfiguration(server: ServerAddress, level: String): File {
                configs += level
                return config
            }
        }
        val vm = GameplayViewModel(server, "lvl", configFetcher, runtime, dispatcher, optionsOnly = true)
        advanceUntilIdle()
        val state = vm.state.value as GameplayUiState.Options
        assertEquals(6, state.options.size)
        assertEquals(1f, state.values.getValue("#SPEED"))
        assertEquals(listOf("lvl"), configs)
        assertEquals(emptyList<String>(), fetches)
        assertEquals(emptyList<LoadedResources>(), runtime.loads)
    }

    @Test
    fun optionsScreenConfigurationFailureShowsRetry() = test {
        val failing = object : LevelFetcher {
            override suspend fun load(server: ServerAddress, level: String, onProgress: (LoadProgress) -> Unit) =
                fetcher.load(server, level, onProgress)

            override suspend fun engineConfiguration(server: ServerAddress, level: String): File =
                throw LoadFailure(ResourceKind.ENGINE_CONFIGURATION, null, IOException("offline"))
        }
        val vm = GameplayViewModel(server, "lvl", failing, runtime, dispatcher, optionsOnly = true)
        advanceUntilIdle()
        assertEquals(GameplayUiState.Failed("engine.configuration: Couldn't reach the server"), vm.state.value)
    }

    @Test
    fun loadsAndSummarises() = test {
        val vm = started()
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
        assertEquals(listOf(resources), runtime.loads)
        assertEquals(listOf("sss-722-1279-expert"), fetches)
    }

    @Test
    fun showsProgressWhileLoading() = test {
        val gate = CompletableDeferred<Unit>()
        val progress = LoadProgress(3, 15, ResourceKind.SKIN_TEXTURE, 1000, 4000)
        fetch = { onProgress ->
            onProgress(progress)
            gate.await()
            resources
        }
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher)
        runCurrent()
        assertEquals(GameplayUiState.Loading(progress), vm.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
    }

    @Test
    fun loadFailureShowsRetry() = test {
        fetch = { throw LoadFailure(ResourceKind.SKIN_TEXTURE, "https://cdn/skin", IOException("timeout")) }
        val vm = started()
        assertEquals(GameplayUiState.Failed("skin.texture: Couldn't reach the server"), vm.state.value)
        assertEquals(emptyList<LoadedResources>(), runtime.loads)
    }

    @Test
    fun retryReloads() = test {
        fetch = { throw LoadFailure(null, null, IOException("offline")) }
        val vm = started()
        assertEquals(GameplayUiState.Failed("Couldn't reach the server"), vm.state.value)

        fetch = { throw LoadFailure(ResourceKind.LEVEL_BGM, null, IOException("offline"), damaged = true) }
        vm.retry()
        advanceUntilIdle()
        assertEquals(
            GameplayUiState.Failed("This download is damaged. Delete the level and download it again."),
            vm.state.value,
        )

        fetch = { throw LoadFailure(ResourceKind.LEVEL_BGM, null, IOException("write failed: ENOSPC (No space left on device)")) }
        vm.retry()
        advanceUntilIdle()
        assertEquals(
            GameplayUiState.Failed("level.bgm: Not enough storage. Delete some levels and try again."),
            vm.state.value,
        )

        fetch = { resources }
        vm.retry()
        assertEquals(GameplayUiState.Loading(null), vm.state.value)
        advanceUntilIdle()
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
        assertEquals(4, fetches.size)
    }

    @Test
    fun nativeErrorShowsWhatFailed() = test {
        runtime.error = "engine.watchData: missing field 'archetypes[3].name'"
        val vm = started()
        assertEquals(GameplayUiState.Failed("engine.watchData: missing field 'archetypes[3].name'"), vm.state.value)
    }

    @Test
    fun unsupportedFeatureNamesIt() = test {
        runtime.error = NativeError.UNSUPPORTED + "Spline"
        val vm = started()
        assertEquals(GameplayUiState.Failed("This level needs Spline, which this app doesn't support"), vm.state.value)
    }

    @Test
    fun serverErrorNamesStatus() = test {
        fetch = {
            throw LoadFailure(
                ResourceKind.LEVEL_DATA,
                "https://cdn/level",
                ServerApiException(404, "HTTP 404"),
            )
        }
        val vm = started()
        assertEquals(GameplayUiState.Failed("level.data: The server returned an error (404)"), vm.state.value)
    }

    @Test
    fun surfaceBeforeReadyStartsOnceLoaded() = test {
        val gate = CompletableDeferred<Unit>()
        fetch = {
            gate.await()
            resources
        }
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher)
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), runtime.calls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("start 0 watch"), runtime.calls)
    }

    @Test
    fun callbackFaultStopsTheLevelAndIsWrittenInDebugBuilds() = test {
        val dir = java.nio.file.Files.createTempDirectory("faults").toFile()
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, faultDir = dir, io = dispatcher)
        advanceUntilIdle()
        vm.pollFault() // nothing faulted yet
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
        runtime.faultText = "runaway loop\nWhile: too many iterations (archetype 'Tap Note', updateSequential)\n12.5"
        vm.pollFault()
        assertEquals(
            GameplayUiState.Stopped("The level stopped: runaway loop: While: too many iterations (archetype 'Tap Note', updateSequential)"),
            vm.state.value,
        )
        advanceUntilIdle()
        val written = dir.listFiles()!!.single().readText()
        assertTrue(written, "archetype: Tap Note" in written && "frame time: 12.5 s" in written && "runaway loop" in written)
        dir.deleteRecursively()
    }

    @Test
    fun startFaultShowsTheStoppedMessageAndWritesTheFaultFile() = test {
        val dir = java.nio.file.Files.createTempDirectory("faults").toFile()
        runtime.startError = "fault:runaway loop: While: x (archetype 'Tap Note', updateSequential)"
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, faultDir = dir, io = dispatcher)
        advanceUntilIdle()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        assertEquals(
            GameplayUiState.Stopped("The level stopped: runaway loop: While: x (archetype 'Tap Note', updateSequential)"),
            vm.state.value,
        )
        val written = dir.listFiles()!!.single().readText()
        assertTrue(written, "archetype: Tap Note" in written && "runaway loop" in written)
        dir.deleteRecursively()
    }

    @Test
    fun surfaceAfterReadyStarts() = test {
        val vm = started()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        vm.surfaceChanged(surfaces[0], 3200, 2136) // same surface and size: no restart
        advanceUntilIdle()
        assertEquals(listOf("start 0 watch"), runtime.calls)
    }

    @Test
    fun destroyedSurfaceStopsAndNeverStartsLate() = test {
        val vm = started()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        vm.surfaceDestroyed()
        assertEquals(listOf("start 0 watch", "stop"), runtime.calls)

        // A start queued before the surface went away finds it gone.
        vm.surfaceChanged(surfaces[1], 3200, 2136)
        vm.surfaceDestroyed()
        advanceUntilIdle()
        assertEquals(listOf("start 0 watch", "stop"), runtime.calls)
    }

    @Test
    fun resizedSurfaceRestarts() = test {
        val vm = started()
        vm.surfaceChanged(surfaces[0], 3200, 2000)
        advanceUntilIdle()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        assertEquals(listOf("start 0 watch", "stop", "start 0 watch"), runtime.calls)
    }

    @Test
    fun startErrorShowsWhatFailed() = test {
        runtime.startError = NativeError.UNSUPPORTED + "Spline"
        val vm = started()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        assertEquals(GameplayUiState.Failed("This level needs Spline, which this app doesn't support"), vm.state.value)
        vm.surfaceDestroyed() // nothing runs: no stop
        assertEquals(listOf("start 0 watch"), runtime.calls)
    }

    @Test
    fun renderRequestRendersInsteadOfStarting() = test {
        val out = File.createTempFile("render", "").apply { delete(); mkdirs() }
        val request = RenderRequest(floatArrayOf(1.5f, 0.25f), out, floatArrayOf(1f, 9.8f))
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, render = request)
        advanceUntilIdle()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        assertEquals(listOf("render 1.5,0.25 options 1.0,9.8 ${out.path}"), runtime.calls)
        assertEquals("ok 2\n", File(out, RenderRequest.DONE_FILE).readText())
        out.deleteRecursively()
    }

    @Test
    fun renderErrorIsWrittenAndShown() = test {
        val out = File.createTempFile("render", "").apply { delete(); mkdirs() }
        runtime.renderError = "renderer: GL 0x0505"
        val request = RenderRequest(floatArrayOf(1f), out, FloatArray(0))
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, render = request)
        advanceUntilIdle()
        assertEquals("error: renderer: GL 0x0505\n", File(out, RenderRequest.DONE_FILE).readText())
        assertEquals(GameplayUiState.Failed("renderer: GL 0x0505"), vm.state.value)
        out.deleteRecursively()
    }

    @Test
    fun unreadableRenderInputIsWrittenAndNothingRuns() = test {
        val out = File.createTempFile("render", "").apply { delete(); mkdirs() }
        val request = RenderRequest(FloatArray(0), out, FloatArray(0), error = "unreadable render input: times.txt")
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, render = request)
        advanceUntilIdle()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), runtime.calls)
        assertEquals("error: unreadable render input: times.txt\n", File(out, RenderRequest.DONE_FILE).readText())
        assertEquals(GameplayUiState.Failed("unreadable render input: times.txt"), vm.state.value)
        out.deleteRecursively()
    }

    @Test
    fun failedLoadIsWrittenForARenderRequest() = test {
        val out = File.createTempFile("render", "").apply { delete(); mkdirs() }
        fetch = { throw LoadFailure(null, null, IOException("down")) }
        val request = RenderRequest(floatArrayOf(1f), out, FloatArray(0))
        GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, render = request)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), runtime.calls)
        assertTrue(File(out, RenderRequest.DONE_FILE).readText().startsWith("error: "))
        out.deleteRecursively()
    }

    @Test
    fun renderValuesFileParses() {
        assertEquals(listOf(0.5f, -1f, 12.25f), RenderRequest.parseValues("0.5\n-1\n\n12.25 \n").toList())
    }

    @Test
    fun clearingDuringNativeLoadMakesNoCallAfterClose() = test {
        val worker = Executors.newSingleThreadExecutor()
        val runtime = BlockingRuntime()
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer { GameplayViewModel(server, "lvl", fetcher, runtime, worker.asCoroutineDispatcher()) }
        }
        try {
            val vm = ViewModelProvider.create(store, factory)[GameplayViewModel::class]
            runCurrent()
            assertTrue("native load never started", runtime.entered.await(5, TimeUnit.SECONDS))

            store.clear() // Back pressed: the scope is cancelled and onCleared closes the runtime.
            assertTrue("runtime never closed", runtime.closed.await(5, TimeUnit.SECONDS))
            runtime.release.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            advanceUntilIdle()

            assertEquals(emptyList<String>(), runtime.callsAfterClose)
            assertEquals(GameplayUiState.Loading(null), vm.state.value)
        } finally {
            runtime.release.countDown()
            worker.shutdownNow()
        }
    }

    /**
     * Blocks inside load until released, and records every call that starts after close. Not
     * synchronized, so it stands for the worst interleaving the real monitor might allow.
     */
    private class BlockingRuntime : LevelRuntime {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val callsAfterClose: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private fun call(name: String) {
            if (closed.count == 0L) callsAfterClose += name
        }

        override fun load(resources: LoadedResources): NativeLoad {
            call("load")
            entered.countDown()
            release.await()
            return NativeLoad.Loaded("97 archetypes, 1080 entities")
        }

        override fun start(
            surface: Surface,
            mode: GameplayMode,
            options: FloatArray,
            settings: FloatArray,
            replayOut: File?,
            replayIn: File?,
        ): String? {
            call("start")
            return null
        }

        override fun stop() = call("stop")

        override fun close() {
            call("close")
            closed.countDown()
        }
    }

    @Test
    fun playStateEndedShowsResult() = test {
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, mode = GameplayMode.Play)
        advanceUntilIdle()
        runtime.playValues = floatArrayOf(PlayState.RUNNING, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        runtime.playHaptics = intArrayOf(1, 3)
        assertEquals(listOf(1, 3), vm.pollPlay().toList())
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)

        runtime.playHaptics = IntArray(0)
        runtime.playValues = floatArrayOf(PlayState.ENDED, 2f, 987654f, 990000f, 321f, 200f, 90f, 20f, 11f, 321f, 0f)
        vm.pollPlay()
        assertEquals(GameplayUiState.Ended(PlayResult(Grade.Pass, 987654, 990000, 321, 200, 90, 20, 11, 321)), vm.state.value)
    }

    private fun TestScope.playing(): GameplayViewModel {
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, mode = GameplayMode.Play)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun backgroundingPausesPlay() = test {
        val vm = playing()
        assertEquals(PlayUi.Running, vm.playUi.value)
        vm.pause() // what GameplayActivity.onPause calls
        assertEquals(listOf("pause"), runtime.playCalls)
        assertEquals(PlayUi.Paused, vm.playUi.value)
        // The view model answers the native state too: a poll must not undo the pause.
        runtime.playValues = floatArrayOf(PlayState.PAUSED, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        vm.pollPlay()
        assertEquals(PlayUi.Paused, vm.playUi.value)
    }

    @Test
    fun watchIgnoresPauseRequests() = test {
        val vm = started()
        vm.pause()
        vm.retryPlay()
        vm.quit()
        assertEquals(emptyList<String>(), runtime.playCalls)
        assertEquals(PlayUi.Running, vm.playUi.value)
    }

    @Test
    fun resumeCountsDownThenRuns() = test {
        val vm = playing()
        vm.pause()
        vm.resumePlay()
        assertEquals(listOf("pause", "resume"), runtime.playCalls)
        assertEquals(PlayUi.Countdown(3), vm.playUi.value)
        // "3", "2", "1" for a third of a second each: remaining 1.0 .. 0.667 is 3, to 0.333 is 2, then 1.
        for ((left, digit) in listOf(0.9f to 3, 0.6f to 2, 0.5f to 2, 0.3f to 1, 0.01f to 1)) {
            runtime.playValues = floatArrayOf(PlayState.COUNTDOWN, left, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            vm.pollPlay()
            assertEquals("left $left", PlayUi.Countdown(digit), vm.playUi.value)
        }
        runtime.playValues = FloatArray(PlayState.FLOATS)
        vm.pollPlay()
        assertEquals(PlayUi.Running, vm.playUi.value)
    }

    @Test
    fun resumeOnlyFromThePauseMenu() = test {
        val vm = playing()
        vm.resumePlay()
        assertEquals(emptyList<String>(), runtime.playCalls)
        assertEquals(PlayUi.Running, vm.playUi.value)
    }

    @Test
    fun retryRestartsAndHidesMenu() = test {
        val vm = playing()
        vm.pause()
        vm.retryPlay()
        assertEquals(listOf("pause", "retry"), runtime.playCalls)
        assertEquals(PlayUi.Running, vm.playUi.value)
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
    }

    @Test
    fun quitEndsWithPartialResult() = test {
        val vm = playing()
        vm.pause()
        vm.quit()
        assertEquals(listOf("pause", "quit"), runtime.playCalls)
        assertEquals(PlayUi.Running, vm.playUi.value) // the menu is gone while the render thread ends the run
        runtime.playValues = floatArrayOf(PlayState.ENDED, 3f, 123456f, 800000f, 40f, 30f, 8f, 1f, 1f, 40f, 1f)
        vm.pollPlay()
        assertEquals(
            GameplayUiState.Ended(PlayResult(Grade.Fail, 123456, 800000, 40, 30, 8, 1, 1, 40), partial = true),
            vm.state.value,
        )
    }

    @Test
    fun aRunThatEndsOnItsOwnIsNotPartial() = test {
        val vm = playing()
        runtime.playValues = floatArrayOf(PlayState.ENDED, 2f, 987654f, 990000f, 321f, 200f, 90f, 20f, 11f, 321f, 0f)
        vm.pollPlay()
        assertEquals(false, (vm.state.value as GameplayUiState.Ended).partial)
    }

    private class FakeResults(var stored: PlayResult? = null, var newBest: Boolean = true) : ResultStore {
        val recorded = mutableListOf<PlayResult>()

        override suspend fun best(server: ServerAddress, level: String) = stored

        override suspend fun record(server: ServerAddress, level: String, result: PlayResult): Boolean {
            recorded += result
            if (newBest) stored = result
            return newBest
        }

        val played = mutableListOf<String>()

        override suspend fun markPlayed(server: ServerAddress, level: String, at: Long) {
            played += level
        }
    }

    @Test
    fun onLoadedRunsAfterSuccessfulLoad() = test {
        val seen = mutableListOf<LoadedResources>()
        GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, io = dispatcher, onLoaded = { seen += it })
        advanceUntilIdle()
        assertEquals(listOf(resources), seen)
    }

    @Test
    fun onLoadedNotRunWhenLoadFails() = test {
        fetch = { throw LoadFailure(null, null, IOException("offline")) }
        val seen = mutableListOf<LoadedResources>()
        GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, io = dispatcher, onLoaded = { seen += it })
        advanceUntilIdle()
        assertEquals(emptyList<LoadedResources>(), seen)
    }

    @Test
    fun onLoadedFailureDoesNotFailTheLevel() = test {
        val vm = GameplayViewModel(
            server, "lvl", fetcher, runtime, dispatcher, io = dispatcher,
            onLoaded = { throw IllegalStateException("save failed") },
        )
        advanceUntilIdle()
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
    }

    @Test
    fun markPlayedWhenReadyNotInOptionsOnly() = test {
        val results = FakeResults()
        GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, results = results, io = dispatcher)
        advanceUntilIdle()
        assertEquals(listOf("lvl"), results.played)

        val optionsResults = FakeResults()
        GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, results = optionsResults, io = dispatcher, optionsOnly = true)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), optionsResults.played)
    }

    private val endedValues = floatArrayOf(PlayState.ENDED, 2f, 987654f, 990000f, 321f, 200f, 90f, 20f, 11f, 321f, 0f)
    private val endedResult = PlayResult(Grade.Pass, 987654, 990000, 321, 200, 90, 20, 11, 321)

    @Test
    fun endedRecordsAndShowsResult() = test {
        val results = FakeResults(stored = PlayResult(Grade.Pass, 500000, 1, 1, 1, 0, 0, 0, 1))
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, mode = GameplayMode.Play, results = results, io = dispatcher)
        advanceUntilIdle()
        runtime.playValues = endedValues
        vm.pollPlay()
        advanceUntilIdle()
        assertEquals(GameplayUiState.Ended(endedResult), vm.state.value)
        assertEquals(listOf(endedResult), results.recorded)
        assertEquals(
            ResultInfo(best = endedResult, isNewBest = true, previousBest = PlayResult(Grade.Pass, 500000, 1, 1, 1, 0, 0, 0, 1)),
            vm.resultInfo.value,
        )
        vm.pollPlay() // a second poll of the same end records nothing more
        advanceUntilIdle()
        assertEquals(1, results.recorded.size)
    }

    @Test
    fun notABestShowsTheStoredOne() = test {
        val kept = PlayResult(Grade.FullCombo, 999999, 1, 1, 1, 0, 0, 0, 1)
        val results = FakeResults(stored = kept, newBest = false)
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, mode = GameplayMode.Play, results = results, io = dispatcher)
        advanceUntilIdle()
        runtime.playValues = endedValues
        vm.pollPlay()
        advanceUntilIdle()
        assertEquals(ResultInfo(best = kept, isNewBest = false, previousBest = kept), vm.resultInfo.value)
    }

    private val oldBest = PlayResult(Grade.Pass, 500000, 1, 1, 1, 0, 0, 0, 1)

    private fun TestScope.endPlay(
        results: ResultStore?,
        partial: Boolean = false,
        history: RunHistory? = null,
        values: FloatArray = endedValues,
    ): GameplayViewModel {
        val vm = GameplayViewModel(
            server, "lvl", fetcher, runtime, dispatcher, mode = GameplayMode.Play, results = results, io = dispatcher,
            history = history, now = { 42L },
        )
        advanceUntilIdle()
        runtime.playValues = values.copyOf().also { if (partial) it[PlayState.PARTIAL] = 1f }
        vm.pollPlay()
        advanceUntilIdle()
        return vm
    }

    private class FakeHistory(var fail: Boolean = false) : RunHistory {
        val appended = mutableListOf<Pair<RunRecord, PlayResult?>>()
        val keys = mutableListOf<my.fumui.sairitsu.data.LevelKey>()

        override suspend fun runs(key: my.fumui.sairitsu.data.LevelKey): List<RunRecord>? = null

        override suspend fun append(key: my.fumui.sairitsu.data.LevelKey, record: RunRecord, seed: PlayResult?) {
            if (fail) throw IOException("disk full")
            keys += key
            appended += record to seed
        }

        val attached = mutableListOf<Triple<my.fumui.sairitsu.data.LevelKey, Long, String>>()

        override suspend fun attachReplay(key: my.fumui.sairitsu.data.LevelKey, at: Long, replay: String): Boolean {
            attached += Triple(key, at, replay)
            return false
        }
    }

    @Test
    fun finishedRunAppendedWithPreviousBestAsSeed() = test {
        val h = FakeHistory()
        endPlay(FakeResults(stored = oldBest), history = h)
        assertEquals(listOf(RunRecord(42L, endedResult) to oldBest), h.appended)
        assertEquals(listOf(my.fumui.sairitsu.data.LevelKey(server.linkAddress, "lvl")), h.keys)
    }

    @Test
    fun firstRunAppendedWithoutSeed() = test {
        val h = FakeHistory()
        endPlay(FakeResults(), history = h)
        assertEquals(listOf(RunRecord(42L, endedResult) to null), h.appended)
    }

    @Test
    fun quitNotAppended() = test {
        val h = FakeHistory()
        endPlay(FakeResults(stored = oldBest), partial = true, history = h)
        assertEquals(emptyList<Pair<RunRecord, PlayResult?>>(), h.appended)
    }

    @Test
    fun failedRunAppended() = test {
        val h = FakeHistory()
        endPlay(FakeResults(), history = h, values = endedValues.copyOf().also { it[1] = 3f })
        assertEquals(1, h.appended.size)
        assertEquals(Grade.Fail, h.appended.single().first.result.grade)
    }

    @Test
    fun historyAppendFailureStillShowsResult() = test {
        val vm = endPlay(FakeResults(stored = oldBest), history = FakeHistory(fail = true))
        assertEquals(ResultInfo(best = endedResult, isNewBest = true, previousBest = oldBest), vm.resultInfo.value)
    }

    @Test
    fun bestStoreFailureStillAppendsRun() = test {
        val failing = object : ResultStore {
            override suspend fun best(server: ServerAddress, level: String): PlayResult? = oldBest
            override suspend fun record(server: ServerAddress, level: String, result: PlayResult): Boolean =
                throw IOException("datastore broken")
        }
        val h = FakeHistory()
        endPlay(failing, history = h)
        assertEquals(listOf(RunRecord(42L, endedResult) to oldBest), h.appended)
    }

    @Test
    fun historyWithoutResultsStoreStillAppends() = test {
        val h = FakeHistory()
        endPlay(null, history = h)
        assertEquals(listOf(RunRecord(42L, endedResult) to null), h.appended)
    }

    @Test
    fun newBestKeepsThePreviousBest() = test {
        val vm = endPlay(FakeResults(stored = oldBest))
        assertEquals(ResultInfo(best = endedResult, isNewBest = true, previousBest = oldBest), vm.resultInfo.value)
    }

    @Test
    fun firstPlayHasNoPreviousBest() = test {
        val vm = endPlay(FakeResults())
        assertEquals(ResultInfo(best = endedResult, isNewBest = true, previousBest = null), vm.resultInfo.value)
    }

    @Test
    fun quitShowsStoredBestAsPrevious() = test {
        val results = FakeResults(stored = oldBest)
        val vm = endPlay(results, partial = true)
        assertEquals(ResultInfo(best = oldBest, isNewBest = false, previousBest = oldBest), vm.resultInfo.value)
        assertEquals(emptyList<PlayResult>(), results.recorded)
    }

    @Test
    fun levelInfoComesFromTheHook() = test {
        val seen = mutableListOf<LoadedResources>()
        val vm = GameplayViewModel(
            server, "lvl", fetcher, runtime, dispatcher, io = dispatcher,
            levelDetails = { seen += it; LevelItem(name = "lvl", title = "T") },
        )
        advanceUntilIdle()
        assertEquals("T", vm.levelInfo.value?.title)
        assertEquals(listOf(resources), seen)
    }

    @Test
    fun levelInfoStaysNullWhenTheHookThrows() = test {
        val vm = GameplayViewModel(
            server, "lvl", fetcher, runtime, dispatcher, io = dispatcher,
            levelDetails = { throw IOException("unreadable") },
        )
        advanceUntilIdle()
        assertEquals(null, vm.levelInfo.value)
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
    }

    @Test
    fun partialRunIsNeverRecorded() = test {
        val results = FakeResults()
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, mode = GameplayMode.Play, results = results, io = dispatcher)
        advanceUntilIdle()
        runtime.playValues = endedValues.copyOf().also { it[PlayState.PARTIAL] = 1f }
        vm.pollPlay()
        advanceUntilIdle()
        assertEquals(emptyList<PlayResult>(), results.recorded)
        assertEquals(true, (vm.state.value as GameplayUiState.Ended).partial)
        assertEquals(ResultInfo(best = null, isNewBest = false), vm.resultInfo.value)
    }

    @Test
    fun retryFromTheResultStartsAgain() = test {
        val vm = playing()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        runtime.playValues = endedValues
        vm.pollPlay()
        assertTrue(vm.state.value is GameplayUiState.Ended)
        vm.retryFromResult()
        advanceUntilIdle()
        assertEquals(listOf("start 0 play", "stop", "start 0 play"), runtime.calls)
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
        assertEquals(null, vm.resultInfo.value)
    }

    @Test
    fun pollAfterRetryFromTheResultDoesNotBringBackTheEnd() = test {
        val results = FakeResults()
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, mode = GameplayMode.Play, results = results, io = dispatcher)
        advanceUntilIdle()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        runtime.playValues = endedValues
        vm.pollPlay()
        advanceUntilIdle()
        assertEquals(1, results.recorded.size)
        vm.retryFromResult()
        vm.retryFromResult() // a double tap
        vm.pollPlay() // playValues still reports ENDED (the old run)
        advanceUntilIdle()
        runtime.playValues = FloatArray(PlayState.FLOATS) // the new run reports RUNNING
        vm.pollPlay()
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
        assertEquals(1, results.recorded.size)
        assertEquals(listOf("start 0 play", "stop", "start 0 play"), runtime.calls) // one restart, not two
    }

    @Test
    fun retryFromTheResultDoesNothingBeforeTheEnd() = test {
        val vm = playing()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        vm.retryFromResult()
        advanceUntilIdle()
        assertEquals(listOf("start 0 play"), runtime.calls)
    }

    @Test
    fun hapticsFollowTheHapticOption() = test {
        val config = File.createTempFile("config", ".json").apply {
            writeText("{\"options\":[{\"name\":\"#HAPTIC\",\"type\":\"toggle\",\"def\":1},{\"name\":\"#X\",\"type\":\"toggle\",\"def\":0}]}")
        }
        val withConfig = LoadedResources(resources.files + (ResourceKind.ENGINE_CONFIGURATION to config))
        fetch = { withConfig }
        val on = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, mode = GameplayMode.Play)
        advanceUntilIdle()
        assertTrue(on.hapticsOn) // the default is on
        fetch = { resources }
        val none = GameplayViewModel(server, "lvl", fetcher, FakeRuntime(), dispatcher, mode = GameplayMode.Play)
        advanceUntilIdle()
        assertEquals(false, none.hapticsOn) // the engine has no #HAPTIC option
    }

    @Test
    fun backgroundedPlayKeepsItsRunAndComesBackPaused() = test {
        val vm = playing()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        vm.pause()
        vm.surfaceDestroyed()
        // The run is kept: the surface is given up, not the level stopped.
        assertEquals(listOf("start 0 play", "detach"), runtime.calls)
        assertEquals(PlayUi.Paused, vm.playUi.value)
        vm.surfaceChanged(surfaces[1], 3200, 2136)
        advanceUntilIdle()
        assertEquals(listOf("start 0 play", "detach", "attach 1"), runtime.calls)
        assertEquals(PlayUi.Paused, vm.playUi.value)
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
    }

    @Test
    fun backgroundedPlayStartsOverWhenTheSurfaceCannotBeKept() = test {
        val vm = playing()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        vm.surfaceDestroyed()
        runtime.attachOk = false
        vm.surfaceChanged(surfaces[1], 3200, 2136)
        advanceUntilIdle()
        assertEquals(listOf("start 0 play", "detach", "attach 1", "stop", "start 1 play"), runtime.calls)
        assertEquals(PlayUi.Running, vm.playUi.value)
    }

    @Test
    fun watchStillStopsWhenTheSurfaceGoes() = test {
        val vm = started()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        vm.surfaceDestroyed()
        assertEquals(listOf("start 0 watch", "stop"), runtime.calls)
    }

    @Test
    fun watchIgnoresPlayState() = test {
        val vm = started()
        runtime.playValues = floatArrayOf(PlayState.ENDED, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        vm.pollPlay()
        assertEquals(GameplayUiState.Ready("97 archetypes, 1080 entities"), vm.state.value)
    }

    private val keyed = LoadedResources(
        ResourceKind.entries.associateWith { File(it.wireName) },
        keys = ResourceKind.entries.associateWith { "key-${it.wireName}" },
    )

    private class ReplayRig(val dir: File, val temp: ReplayTemp, val store: ReplayStore) {
        val history = FakeHistory()
        val library = mutableListOf<LoadedResources>()
        var libraryFails = false

        /** When set, the library add waits for it before it completes. */
        var libraryGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    }

    private fun newRig(createTemp: Boolean = true): ReplayRig {
        val root = java.nio.file.Files.createTempDirectory("replay-test").toFile().apply { deleteOnExit() }
        val temp = ReplayTemp(File(root, "tmp/last.json.gz"), File(root, "tmp/last.config.gz"))
        if (createTemp) {
            temp.replay.parentFile!!.mkdirs()
            temp.replay.writeText("replay")
            temp.config.writeText("config")
        }
        return ReplayRig(File(root, "saved"), temp, ReplayStore(File(root, "saved"), dispatcher))
    }

    private fun TestScope.replayPlaying(rig: ReplayRig): GameplayViewModel {
        fetch = { keyed }
        val vm = GameplayViewModel(
            server, "lvl", fetcher, runtime, dispatcher, mode = GameplayMode.Play, io = dispatcher,
            history = rig.history, now = { 42L },
            replayTemp = rig.temp, replays = rig.store,
            addToLibrary = { loaded ->
                rig.libraryGate?.await()
                if (rig.libraryFails) throw IOException("no space")
                rig.library += loaded
            },
        )
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.endRun(vm: GameplayViewModel, partial: Boolean = false) {
        runtime.playValues = endedValues.copyOf().also { if (partial) it[PlayState.PARTIAL] = 1f }
        vm.pollPlay()
        runCurrent()
    }

    @Test
    fun playStartPassesReplayTempBase() = test {
        val rig = newRig()
        val vm = replayPlaying(rig)
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        assertEquals(listOf<File?>(File(rig.temp.replay.path.removeSuffix(".json.gz"))), runtime.replayOuts)
        assertTrue(rig.temp.base.path.endsWith("last"))

        val watchRuntime = FakeRuntime()
        val watch = GameplayViewModel(server, "lvl", fetcher, watchRuntime, dispatcher, replayTemp = rig.temp)
        advanceUntilIdle()
        watch.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        assertEquals(listOf<File?>(null), watchRuntime.replayOuts)
    }

    @Test
    fun runRecordCarriesLevelAndEngineKeys() = test {
        val rig = newRig()
        val vm = replayPlaying(rig)
        endRun(vm)
        val record = rig.history.appended.single().first
        assertEquals(keyed.keys[ResourceKind.LEVEL_DATA], record.levelData)
        assertEquals(engineKey(keyed.keys), record.engine)
        assertEquals(42L, record.at)
    }

    @Test
    fun replaySaveBecomesReadyWhenNativeIsReady() = test {
        val rig = newRig()
        val vm = replayPlaying(rig)
        runtime.replayStatusValue = ReplayStatus.Writing
        endRun(vm)
        assertEquals(ReplaySave.Preparing, vm.replaySave.value)
        advanceTimeBy(300)
        assertEquals(ReplaySave.Preparing, vm.replaySave.value)
        runtime.replayStatusValue = ReplayStatus.Ready
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ReplaySave.Ready, vm.replaySave.value)
    }

    @Test
    fun replayNeverReadyFailsAfterFiveSeconds() = test {
        val rig = newRig()
        val vm = replayPlaying(rig)
        runtime.replayStatusValue = ReplayStatus.Writing
        endRun(vm)
        advanceTimeBy(4_000)
        assertEquals(ReplaySave.Preparing, vm.replaySave.value)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(ReplaySave.Failed("Couldn't save the replay"), vm.replaySave.value)
    }

    @Test
    fun nativeFailedStatusFailsTheSave() = test {
        val rig = newRig()
        val vm = replayPlaying(rig)
        runtime.replayStatusValue = ReplayStatus.Failed
        endRun(vm)
        assertEquals(ReplaySave.Failed("Couldn't save the replay"), vm.replaySave.value)
        assertEquals("Couldn't save the replay", vm.message.value)
        vm.messageShown()
        advanceUntilIdle()
        assertEquals(null, vm.message.value) // shown once
    }

    @Test
    fun libraryAddSurvivesRetryFromResult() = test {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val rig = newRig().apply { libraryGate = gate }
        val vm = readyReplay(rig)
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        vm.saveReplay()
        runCurrent() // copied and attached; the add is now suspended
        assertEquals(ReplaySave.Saved, vm.replaySave.value)
        vm.retryFromResult()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(keyed), rig.library)
    }

    @Test
    fun quitHasNoReplay() = test {
        val rig = newRig()
        val vm = replayPlaying(rig)
        runtime.replayStatusValue = ReplayStatus.Ready
        endRun(vm, partial = true)
        advanceUntilIdle()
        assertEquals(ReplaySave.Unavailable, vm.replaySave.value)
    }

    @Test
    fun noReplayTempMeansUnavailable() = test {
        val vm = playing()
        runtime.replayStatusValue = ReplayStatus.Ready
        endRun(vm)
        advanceUntilIdle()
        assertEquals(ReplaySave.Unavailable, vm.replaySave.value)
    }

    private fun TestScope.readyReplay(rig: ReplayRig): GameplayViewModel {
        val vm = replayPlaying(rig)
        runtime.replayStatusValue = ReplayStatus.Ready
        endRun(vm)
        assertEquals(ReplaySave.Ready, vm.replaySave.value)
        return vm
    }

    @Test
    fun saveReplaySavesAndAttaches() = test {
        val rig = newRig()
        val vm = readyReplay(rig)
        vm.saveReplay()
        advanceUntilIdle()
        assertEquals(ReplaySave.Saved, vm.replaySave.value)
        val files = rig.dir.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(2, files.size)
        val id = files.first { it.endsWith(".json.gz") }.removeSuffix(".json.gz")
        assertEquals(listOf("$id.config.gz", "$id.json.gz"), files)
        assertEquals(
            listOf(Triple(my.fumui.sairitsu.data.LevelKey(server.linkAddress, "lvl"), 42L, id)),
            rig.history.attached,
        )
        assertEquals(listOf(keyed), rig.library)
        assertEquals(null, vm.message.value)
        vm.saveReplay() // Saved: a second tap does nothing
        advanceUntilIdle()
        assertEquals(2, rig.dir.listFiles().orEmpty().size)
    }

    @Test
    fun saveReplayLibraryFailureStillSaved() = test {
        val rig = newRig().apply { libraryFails = true }
        val vm = readyReplay(rig)
        vm.saveReplay()
        advanceUntilIdle()
        assertEquals(ReplaySave.Saved, vm.replaySave.value)
        assertEquals("Replay saved, but the level couldn't be added to your library", vm.message.value)
        vm.messageShown()
        assertEquals(null, vm.message.value)
    }

    @Test
    fun saveReplayStoreFailureStaysReady() = test {
        val rig = newRig()
        val vm = readyReplay(rig)
        rig.temp.config.delete() // the copy fails with an IOException
        vm.saveReplay()
        advanceUntilIdle()
        assertEquals(ReplaySave.Ready, vm.replaySave.value)
        assertEquals("Couldn't save the replay", vm.message.value)
        assertEquals(emptyList<Triple<my.fumui.sairitsu.data.LevelKey, Long, String>>(), rig.history.attached)
        assertEquals(emptyList<LoadedResources>(), rig.library)
    }

    @Test
    fun saveReplayDoesNothingUnlessReady() = test {
        val rig = newRig()
        val vm = replayPlaying(rig)
        runtime.replayStatusValue = ReplayStatus.Writing
        endRun(vm)
        vm.saveReplay()
        advanceTimeBy(100)
        assertEquals(ReplaySave.Preparing, vm.replaySave.value)
        assertTrue(rig.dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun retryFromResultResetsReplaySave() = test {
        val rig = newRig()
        val vm = readyReplay(rig)
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        vm.retryFromResult()
        advanceUntilIdle()
        assertEquals(ReplaySave.Unavailable, vm.replaySave.value)
    }

    @Test
    fun retryFromResultStopsPreparing() = test {
        val rig = newRig()
        val vm = replayPlaying(rig)
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        runtime.replayStatusValue = ReplayStatus.Writing
        endRun(vm)
        vm.retryFromResult()
        runtime.replayStatusValue = ReplayStatus.Ready // the old run's poll must not resurrect the state
        advanceTimeBy(1_000)
        assertEquals(ReplaySave.Unavailable, vm.replaySave.value)
    }

    @Test
    fun staleReplayPollAfterRetryCannotEnableSaveForAQuit() = test {
        val rig = newRig()
        val vm = replayPlaying(rig)
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        advanceUntilIdle()
        runtime.replayStatusValue = ReplayStatus.Ready
        // Retry lands while the poll is inside the native call; the call then still answers Ready.
        runtime.duringReplayStatus = {
            runtime.duringReplayStatus = null
            vm.retryFromResult()
        }
        // retryFromResult needs the Ended state, so end the run first without the poll running yet.
        runtime.playValues = endedValues
        vm.pollPlay()
        runCurrent()
        advanceUntilIdle()
        // The next run is a quit.
        runtime.playValues = FloatArray(PlayState.FLOATS)
        vm.pollPlay()
        endRun(vm, partial = true)
        advanceUntilIdle()
        assertTrue(vm.state.value is GameplayUiState.Ended)
        assertEquals(ReplaySave.Unavailable, vm.replaySave.value)
        vm.saveReplay()
        advanceUntilIdle()
        assertEquals(ReplaySave.Unavailable, vm.replaySave.value)
        assertTrue(rig.dir.listFiles().orEmpty().isEmpty())
    }

    // ---- Watch a saved replay ----

    private val replayId = "0b6e2f0a-7d36-4c1c-9a53-6f3f4d2c1a10"
    private val sameRef get() = ReplayRef(replayId, keyed.keys[ResourceKind.LEVEL_DATA], engineKey(keyed.keys))

    /** A replay store with a readable config (options [1, 9.8]) for [replayId]. */
    private fun replayStore(config: Boolean = true): ReplayStore {
        val dir = java.nio.file.Files.createTempDirectory("watch-replay").toFile().apply { deleteOnExit() }
        val store = ReplayStore(dir, dispatcher)
        store.replayFile(replayId).writeText("replay")
        if (config) {
            java.util.zip.GZIPOutputStream(store.configFile(replayId).outputStream()).use {
                it.write("""{"options":[1,9.8]}""".toByteArray())
            }
        }
        return store
    }

    private fun TestScope.watching(ref: ReplayRef?, store: ReplayStore?): GameplayViewModel {
        fetch = { keyed }
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, io = dispatcher, replay = ref, replays = store)
        runCurrent()
        return vm
    }

    @Test
    fun tornReplayFailingInNativeStartShowsCantBeRead() = test {
        val vm = watching(sameRef, replayStore())
        runtime.startError = "replay: gzip: input too short"
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        runCurrent()
        assertEquals(GameplayUiState.Stopped("This replay can't be read"), vm.state.value)
    }

    @Test
    fun replayUsesSavedConfigOptions() = test {
        val store = replayStore()
        val vm = watching(sameRef, store)
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        runCurrent()
        assertEquals(listOf(listOf(1f, 9.8f)), runtime.startOptions)
        assertEquals(listOf<File?>(store.replayFile(replayId)), runtime.replayIns)
        assertEquals(null, vm.note.value)
    }

    @Test
    fun chartChangedBlocksPlayback() = test {
        val vm = watching(sameRef.copy(levelData = "older"), replayStore())
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        runCurrent()
        assertEquals(
            GameplayUiState.Stopped("This replay was recorded on an older version of this chart"),
            vm.state.value,
        )
        assertEquals(emptyList<String>(), runtime.calls)
    }

    @Test
    fun engineChangedPlaysWithNote() = test {
        val vm = watching(sameRef.copy(engine = "older"), replayStore())
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        runCurrent()
        assertEquals(listOf("start 0 watch"), runtime.calls)
        val text = "The engine was updated since this run; playback may not match exactly"
        assertEquals(text, vm.note.value)
        advanceTimeBy(4_900)
        assertEquals(text, vm.note.value)
        advanceTimeBy(200)
        assertEquals(null, vm.note.value)
    }

    @Test
    fun unreadableReplayShowsCantBeRead() = test {
        val vm = watching(sameRef, replayStore(config = false))
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        runCurrent()
        assertEquals(GameplayUiState.Stopped("This replay can't be read"), vm.state.value)
        assertEquals(emptyList<String>(), runtime.calls)
    }

    @Test
    fun unreadableIsCheckedBeforeTheMatch() = test {
        val vm = watching(sameRef.copy(levelData = "older"), replayStore(config = false))
        assertEquals(GameplayUiState.Stopped("This replay can't be read"), vm.state.value)
    }

    @Test
    fun seekWhilePausedStaysPaused() = test {
        val vm = started()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        runCurrent()
        vm.toggleWatchPause()
        vm.seek(30f, false)
        assertEquals(listOf("pause true", "seek 30.0 false"), runtime.watchCalls)
    }

    @Test
    fun watchResumesAtRememberedPosition() = test {
        val vm = started()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        runCurrent()
        runtime.watchValues = FloatArray(WatchState.FLOATS).also {
            it[WatchState.TIME] = 42f
            it[WatchState.PAUSED] = 1f
        }
        vm.surfaceDestroyed()
        vm.surfaceChanged(surfaces[1], 3200, 2136)
        runCurrent()
        assertEquals(listOf("start 0 watch", "stop", "start 1 watch"), runtime.calls)
        assertEquals(listOf("repeat false", "seek 42.0 false", "pause true"), runtime.watchCalls)
    }

    @Test
    fun watchResumesRepeatOnBeforeSeek() = test {
        val vm = started()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        runCurrent()
        runtime.watchValues = FloatArray(WatchState.FLOATS).also {
            it[WatchState.TIME] = 7f
            it[WatchState.REPEAT] = 1f
        }
        vm.surfaceDestroyed()
        vm.surfaceChanged(surfaces[1], 3200, 2136)
        runCurrent()
        assertEquals(listOf("repeat true", "seek 7.0 false"), runtime.watchCalls)
    }

    @Test
    fun playIgnoresWatchControlsAndMemory() = test {
        val vm = GameplayViewModel(server, "lvl", fetcher, runtime, dispatcher, mode = GameplayMode.Play)
        advanceUntilIdle()
        vm.surfaceChanged(surfaces[0], 3200, 2136)
        runCurrent()
        runtime.watchValues = FloatArray(WatchState.FLOATS).also { it[WatchState.TIME] = 42f }
        vm.toggleWatchPause()
        vm.seek(3f, true)
        vm.surfaceDestroyed()
        vm.surfaceChanged(surfaces[1], 3200, 2136)
        runCurrent()
        assertEquals(emptyList<String>(), runtime.watchCalls)
    }

    private inner class FakeRuntime : LevelRuntime {
        var playValues = FloatArray(PlayState.FLOATS)
        var playHaptics = IntArray(0)

        override fun playState(out: FloatArray): IntArray {
            playValues.copyInto(out)
            return playHaptics
        }

        val playCalls = mutableListOf<String>()
        var attachOk = true

        override fun pause() { playCalls += "pause" }

        override fun resume() { playCalls += "resume" }

        override fun retry() { playCalls += "retry" }

        override fun quit() { playCalls += "quit" }

        override fun detachSurface(): Boolean {
            calls += "detach"
            return true
        }

        override fun attachSurface(surface: Surface): Boolean {
            calls += "attach ${surfaces.indexOf(surface)}"
            return attachOk
        }

        val loads = mutableListOf<LoadedResources>()
        var error: String? = null
        var startError: String? = null
        var renderError: String? = null
        var faultText: String? = null

        /** "start <surface> <mode>", "stop" and "render <times> options <options> <dir>", in call order. */
        val calls = mutableListOf<String>()

        override fun load(resources: LoadedResources): NativeLoad {
            loads += resources
            return error?.let { NativeLoad.Failed(it) } ?: NativeLoad.Loaded("97 archetypes, 1080 entities")
        }

        var replayStatusValue = ReplayStatus.None
        val replayOuts = mutableListOf<File?>()

        /** Runs inside [replayStatus], standing for something that happens while the blocking native call is in flight. */
        var duringReplayStatus: (() -> Unit)? = null

        override fun replayStatus(): ReplayStatus {
            duringReplayStatus?.invoke()
            return replayStatusValue
        }

        /** The Watch controls, in call order ("pause true", "seek 30.0 false", ...). */
        val watchCalls = mutableListOf<String>()
        var watchValues: FloatArray? = null
        val startOptions = mutableListOf<List<Float>>()
        val replayIns = mutableListOf<File?>()

        override fun watchPause(paused: Boolean) { watchCalls += "pause $paused" }

        override fun seek(time: Float, scrubbing: Boolean) { watchCalls += "seek $time $scrubbing" }

        override fun setRepeat(on: Boolean) { watchCalls += "repeat $on" }

        override fun showBar() { watchCalls += "bar" }

        override fun watchState(out: FloatArray): Boolean {
            val values = watchValues ?: return false
            values.copyInto(out)
            return true
        }

        override fun start(
            surface: Surface,
            mode: GameplayMode,
            options: FloatArray,
            settings: FloatArray,
            replayOut: File?,
            replayIn: File?,
        ): String? {
            calls += "start ${surfaces.indexOf(surface)} ${mode.wire}"
            startOptions += options.toList()
            replayIns += replayIn
            replayOuts += replayOut
            return startError
        }

        override fun stop() {
            calls += "stop"
        }

        override fun renderAtTimes(times: FloatArray, options: FloatArray, outDir: String): String? {
            calls += "render ${times.joinToString(",")} options ${options.joinToString(",")} $outDir"
            return renderError
        }

        override fun fault(): String? = faultText

        override fun close() = Unit
    }

    // Unit tests run against android.jar stubs that return defaults, so these are plain distinct objects.
    private val surfaces = List(2) { Surface(SurfaceTexture(0)) }
}
