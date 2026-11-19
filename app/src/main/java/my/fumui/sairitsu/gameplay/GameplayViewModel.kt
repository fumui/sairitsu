package my.fumui.sairitsu.gameplay

import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.ServerApiException
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.gameplay.model.MissingResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

sealed interface GameplayUiState {
    data class Loading(val progress: LoadProgress?) : GameplayUiState

    /** [message] says what failed; the screen offers Retry and Back. */
    data class Failed(val message: String) : GameplayUiState

    data class Ready(val summary: String) : GameplayUiState

    /**
     * The "Level options" screen: [options] with their [values] (by name; defaults where unsaved), the app [settings]
     * and this level's input [offsets].
     */
    data class Options(
        val options: List<EngineOption>,
        val values: Map<String, Float>,
        val settings: AppSettings,
        val offsets: InputOffsets = InputOffsets(),
        val items: ItemPickerState = ItemPickerState(),
    ) : GameplayUiState

    /** A callback fault stopped the level (it stays on its last frame); the screen shows [message] and Back. */
    data class Stopped(val message: String) : GameplayUiState

    /**
     * A Play run reached its end; the level stays on its last frame. [partial]: the player quit, so [result] is the
     * stats so far (Exit goes to the result with them); the result screen never records it as best.
     */
    data class Ended(val result: PlayResult, val partial: Boolean = false) : GameplayUiState
}

/**
 * What the result screen shows besides the run: the stored [best] (null when none), whether this run became it, and
 * the [previousBest] stored before this run was recorded (what the score is compared with).
 */
data class ResultInfo(val best: PlayResult?, val isNewBest: Boolean, val previousBest: PlayResult? = null)

/** The result screen's Apply button: a run's correction can be applied once. */
enum class OffsetApply { Idle, Saving, Applied }

/** One row of the Apply dialog: [scope]'s [current] input offset and the [suggested] one, in seconds. */
data class OffsetChoice(val scope: OffsetScope, val current: Float, val suggested: Float)

/** The Apply dialog: the run's correction and a choice per scope, narrowest first. */
data class OffsetDialog(val correctionMs: Int, val choices: List<OffsetChoice>)

/** What the Play layer above the stage shows while a run is [GameplayUiState.Ready]. */
sealed interface PlayUi {
    data object Running : PlayUi

    /** The pause menu is up. */
    data object Paused : PlayUi

    /** The silent countdown after Resume; [digit] is 3, 2 or 1 (a third of a second each). */
    data class Countdown(val digit: Int) : PlayUi
}

/** Fetches a level's files: [LevelLoader] in the app, a fake in tests. */
fun interface LevelFetcher {
    suspend fun load(server: ServerAddress, level: String, onProgress: (LoadProgress) -> Unit): LoadedResources

    /** Just the level's engine configuration (the "Level options" screen needs nothing else); default: a full load. */
    suspend fun engineConfiguration(server: ServerAddress, level: String): File? =
        load(server, level) {}.files[ResourceKind.ENGINE_CONFIGURATION]
}

class GameplayViewModel(
    private val server: ServerAddress,
    private val level: String,
    private val fetcher: LevelFetcher,
    private val runtime: LevelRuntime,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val mode: GameplayMode = GameplayMode.Watch,
    /** Debug render-at-time: when set, the loaded level is rendered into PNGs instead of run on the surface. */
    private val render: RenderRequest? = null,
    /** Debug-only [NativeSettings] values that replace the saved settings (comparison runs); null: the saved ones. */
    private val settingsOverride: FloatArray? = null,
    /** Saved Level Option values (per [engine]) and settings; null: defaults, nothing saved. */
    private val store: SettingsStore? = null,
    /** Which engine the saved options belong to. */
    private val engine: String = "default",
    /** Show the options screen once loaded instead of running the level. */
    private val optionsOnly: Boolean = false,
    /** Debug builds: where a fault is written (`<timestamp>.txt`); null: nowhere. */
    private val faultDir: File? = null,
    /** Debug builds: option values (by name) saved into [store] before the level starts (the replay `config` import). */
    private val importOptions: Map<String, Float>? = null,
    /** Where fault files are written (file IO stays off the main thread). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Play: where the best result per level is kept; null: nothing is recorded. */
    private val results: ResultStore? = null,
    /** Play: where every finished run is appended; null: no history. */
    private val history: RunHistory? = null,
    /** The time a run is stamped with (epoch ms). */
    private val now: () -> Long = System::currentTimeMillis,
    /** Runs (on [io]) after the level's files loaded, e.g. "Save levels I play"; its failure never affects the level. */
    private val onLoaded: (suspend (LoadedResources) -> Unit)? = null,
    /** Runs (on [io]) after the level's files loaded: the level's item for the result screen; a failure leaves it null. */
    private val levelDetails: (suspend (LoadedResources) -> LevelItem?)? = null,
    /** Play: where the native side writes the finished run's replay; null: no replay is recorded. */
    private val replayTemp: ReplayTemp? = null,
    /** Where "Save replay" copies the temporary files to; null: replays can't be saved. */
    private val replays: ReplayStore? = null,
    /** Adds the level to the library when it isn't there (on [io]); throws on failure. */
    private val addToLibrary: (suspend (LoadedResources) -> Unit)? = null,
    /** Watch: a saved replay (in [replays]) to play back instead of the chart's autoplay; null: the autoplay. */
    private val replay: ReplayRef? = null,
    /** The items saved on this device, for the pickers of the options screen. */
    private val savedItems: () -> List<SavedItem> = { emptyList() },
) : ViewModel() {
    private val _levelInfo = MutableStateFlow<LevelItem?>(null)

    /** The played level's title, artists, rating and cover; null until read, or when it could not be. */
    val levelInfo: StateFlow<LevelItem?> = _levelInfo.asStateFlow()

    private val _state = MutableStateFlow<GameplayUiState>(GameplayUiState.Loading(null))
    val state: StateFlow<GameplayUiState> = _state.asStateFlow()

    private var job: Job? = null

    // What the level starts with, read at load (the saved options, settings and the engine's option list).
    private var engineOptions: List<EngineOption> = emptyList()
    private var savedOptions: Map<String, Float> = emptyMap()
    private var appSettings = AppSettings()

    @Volatile
    private var inputOffsets = InputOffsets()
    private var lastSummary: String? = null

    /** A surface at one size: the level restarts when either changes, so Runtime Environment's aspect is right. */
    private data class Target(val surface: Surface, val width: Int, val height: Int)

    // Guards surface and running; the runtime's start and stop happen under it, so a stop for a destroyed surface
    // waits for a start in progress, and a start that comes later sees the surface gone.
    private val surfaceLock = Any()
    private var surface: Target? = null
    private var running: Target? = null

    // Play in the background: the run is kept paused with its surface given up (not running, not stopped).
    private var detached: Target? = null

    init {
        load()
    }

    fun retry() {
        if (_state.value is GameplayUiState.Failed) load()
    }

    /** The surface to run on exists at this size (SurfaceHolder.Callback.surfaceChanged). Starts once Ready. */
    fun surfaceChanged(surface: Surface, width: Int, height: Int) {
        val target = Target(surface, width, height)
        val kept = synchronized(surfaceLock) {
            if (this.surface == target) return
            val old = detached
            // Play coming back from the background: the paused run takes the new surface (same size) and shows its
            // frame. Anything else starts over.
            val reattached = old != null && old.width == width && old.height == height && runtime.attachSurface(surface)
            if (reattached) {
                detached = null
                running = target
            } else {
                stopLocked()
            }
            this.surface = target
            reattached
        }
        if (!kept) startIfReady()
    }

    /**
     * The surface is going away, or the screen is left: stops the level and returns once the render thread has
     * let go of the surface (SurfaceHolder.Callback.surfaceDestroyed must not return before). A running Play is not
     * stopped but paused and kept (the menu is up when it comes back); if that fails it is stopped too.
     */
    fun surfaceDestroyed() = synchronized(surfaceLock) {
        surface = null
        val current = running
        if (isPlay && current != null && _state.value is GameplayUiState.Ready && runtime.detachSurface()) {
            detached = current
            running = null
            _playUi.value = PlayUi.Paused
        } else {
            stopLocked()
        }
    }

    private fun stopLocked() {
        // Watch remembers where it was (and whether paused) so the restart that follows a backgrounding or a surface
        // change picks up there.
        if (running != null && !isPlay && render == null) {
            val buffer = FloatArray(WatchState.FLOATS)
            if (runtime.watchState(buffer)) {
                resumeAt = buffer[WatchState.TIME]
                resumePaused = buffer[WatchState.PAUSED] != 0f
                resumeRepeat = buffer[WatchState.REPEAT] != 0f
            }
        }
        if (running != null || detached != null) runtime.stop()
        running = null
        detached = null
    }

    private fun startIfReady(): Job? {
        if (_state.value !is GameplayUiState.Ready || render != null) return null
        return viewModelScope.launch(work) {
            val error = synchronized(surfaceLock) {
                val target = surface ?: return@launch
                if (running == target) return@launch
                // A replay plays with exactly the options it was recorded with, not the saved Level Options.
                val options = replayOptions
                    ?: optionBlock(engineOptions, savedOptions, watch = mode == GameplayMode.Watch)
                val replayOut = if (mode == GameplayMode.Play) replayTemp?.base else null
                val replayIn = replay?.takeIf { replayOptions != null }?.let { replays?.replayFile(it.id) }
                val settings = settingsOverride ?: appSettings.copy(inputOffset = inputOffsets.total).toNative()
                runtime.start(target.surface, mode, options, settings, replayOut, replayIn)
                    .also {
                        if (it == null) {
                            running = target
                            _playUi.value = PlayUi.Running // a fresh run: no menu
                            if (!isPlay) resumeWatch()
                        }
                    }
            }
            if (error != null) {
                Log.w(TAG, "Start of $level failed: $error")
                _state.value = if (NativeError.isFault(error)) {
                    val reason = NativeError.message(error).removePrefix(NativeError.faultMessage(""))
                    faultDir?.let { dir -> launch(io) { writeFault(dir, reason.substringBefore(": "), reason.substringAfter(": "), "start") } }
                    GameplayUiState.Stopped(NativeError.message(error))
                } else if (replay != null && !isPlay && error.startsWith("replay:")) {
                    // A torn or corrupt replay file only shows up when the native side reads it.
                    GameplayUiState.Stopped(REPLAY_UNREADABLE)
                } else {
                    GameplayUiState.Failed(NativeError.message(error))
                }
            }
        }
    }

    /**
     * Polled by the screen while the level runs: a callback fault that stopped it moves to [GameplayUiState.Stopped]
     * ("The level stopped: ..."; the level stays on its last frame) and, in debug builds, is written to [faultDir].
     */
    fun pollFault() {
        if (_state.value !is GameplayUiState.Ready) return
        val text = runtime.fault() ?: return
        val lines = text.split('\n')
        val kind = lines.getOrElse(0) { "error" }
        val detail = lines.getOrElse(1) { "" }
        val time = lines.getOrElse(2) { "" }
        Log.w(TAG, "Level $level stopped at $time s: $kind: $detail")
        _state.value = GameplayUiState.Stopped(NativeError.faultMessage("$kind: $detail"))
        faultDir?.let { dir -> viewModelScope.launch(io) { writeFault(dir, kind, detail, time) } }
    }

    private fun writeFault(dir: File, kind: String, detail: String, time: String) {
        try {
            dir.mkdirs()
            val archetype = ARCHETYPE.find(detail)?.groupValues?.get(1) ?: "unknown"
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.ROOT).format(java.util.Date())
            File(dir, "$stamp.txt").writeText("level: $level\nfault: $kind\narchetype: $archetype\nframe time: $time s\ndetail: $detail\n")
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the fault file: $e")
        }
    }

    /** The built-in HUD (both modes) and Watch's bar for the Compose layer (see [NativeRuntime.overlay]). */
    fun overlay(out: FloatArray): String? = runtime.overlay(out)

    /** "Show FPS" (the saved setting; off without a store). */
    val showFps: StateFlow<Boolean> = store?.showFps?.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        ?: MutableStateFlow(false)

    /** The render thread's frames per second, for the FPS counter. */
    fun renderFps(): Float = runtime.renderFps()

    val isPlay: Boolean get() = mode == GameplayMode.Play

    // ---- Watch's controls and position memory ----

    // The position a stopped Watch was at; the next successful start seeks there and pauses again if it was paused.
    private var resumeAt: Float? = null
    private var resumePaused = false
    private var resumeRepeat = false
    private var watchPaused = false

    private val _note = MutableStateFlow<String?>(null)

    /** A short note over the replay (the engine changed since the run); goes away by itself. */
    val note: StateFlow<String?> = _note.asStateFlow()

    private fun canControlWatch() = !isPlay && _state.value is GameplayUiState.Ready

    /** Called under [surfaceLock] right after a successful Watch start. */
    private fun resumeWatch() {
        val at = resumeAt
        resumeAt = null
        watchPaused = false
        if (at == null) return
        runtime.setRepeat(resumeRepeat)
        runtime.seek(at, false)
        if (resumePaused) {
            runtime.watchPause(true)
            watchPaused = true
        }
    }

    /** Watch: the bar's state and hit rects (see [WatchState]); false when nothing runs. Cheap. */
    fun watchState(out: FloatArray): Boolean = !isPlay && runtime.watchState(out)

    /** Watch: pauses, or continues if paused (the native state when it can say, else what was last asked). */
    fun toggleWatchPause() {
        if (!canControlWatch()) return
        val buffer = FloatArray(WatchState.FLOATS)
        val paused = if (runtime.watchState(buffer)) buffer[WatchState.PAUSED] != 0f else watchPaused
        watchPaused = !paused
        runtime.watchPause(watchPaused)
    }

    fun setRepeat(on: Boolean) {
        if (canControlWatch()) runtime.setRepeat(on)
    }

    /**
     * Watch: jumps to music time [time]. [scrubbing]: the bar is being dragged (audio stays paused); every drag must
     * end with a seek that is not scrubbing, which restarts the audio. A paused Watch stays paused.
     */
    fun seek(time: Float, scrubbing: Boolean) {
        if (canControlWatch()) runtime.seek(time, scrubbing)
    }

    fun showBar() {
        if (canControlWatch()) runtime.showBar()
    }

    /**
     * Pointers from the activity, in Play only. Never waits for the surface lock or the runtime's lock (`start` holds
     * them for seconds): the native side queues them for the render thread.
     */
    fun touch(event: PointerSource) {
        if (isPlay) TouchForwarder.forward(event, runtime::touch)
    }

    private val playBuffer = FloatArray(PlayState.FLOATS)

    private val _playUi = MutableStateFlow<PlayUi>(PlayUi.Running)

    /** The pause menu and the countdown (Play only; always [PlayUi.Running] in Watch). */
    val playUi: StateFlow<PlayUi> = _playUi.asStateFlow()

    /** The engine's Menu widget box for the pause control's hit target (see [NativeRuntime.menuRect]). */
    fun menuRect(out: FloatArray): Boolean = isPlay && runtime.menuRect(out)

    private fun canControlPlay() = isPlay && _state.value is GameplayUiState.Ready

    // Each request sets the Play layer at once and the native side writes its state at once too, so the next poll
    // already agrees (the render thread follows a frame later).

    /** Opens the pause menu: the stage freezes, audio is cut, touches are dropped (the Back button, the pause button, backgrounding). */
    fun pause() {
        if (!canControlPlay()) return
        runtime.pause()
        _playUi.value = PlayUi.Paused
    }

    /** The menu's Resume: the silent 3, 2, 1 countdown, then the run goes on from the paused time. */
    fun resumePlay() {
        if (!canControlPlay() || _playUi.value != PlayUi.Paused) return
        runtime.resume()
        _playUi.value = PlayUi.Countdown(3)
    }

    /** The menu's Retry: the run starts over at once (no countdown) and the menu goes. */
    fun retryPlay() {
        if (!canControlPlay()) return
        runtime.retry()
        _playUi.value = PlayUi.Running
    }

    /**
     * The menu's Quit: the run ends with the stats so far; [pollPlay] then moves to [GameplayUiState.Ended] with
     * `partial` set.
     */
    fun quit() {
        if (!canControlPlay()) return
        runtime.quit()
        _playUi.value = PlayUi.Running
    }

    /**
     * Polled by the screen at each UI frame while a Play runs: follows the native state (the countdown's digit), moves
     * to [GameplayUiState.Ended] with the result once the run ended, and returns the haptic levels queued since the
     * last call (drained; Haptics vibrates them).
     */
    fun pollPlay(): IntArray {
        if (!isPlay || restarting.get() || _state.value !is GameplayUiState.Ready) return IntArray(0)
        val haptics = runtime.playState(playBuffer)
        when (playBuffer[PlayState.STATE]) {
            PlayState.ENDED -> {
                val result = PlayResult.fromNative(playBuffer)
                val partial = playBuffer[PlayState.PARTIAL] == 1f
                Log.i(TAG, "Play of $level ${if (partial) "quit" else "ended"}: $result")
                _playUi.value = PlayUi.Running
                _resultInfo.value = null
                _timing.value = TimingStats.fromNative(runtime.playTimings())
                _offsetDialog.value = null
                _offsetApply.value = OffsetApply.Idle
                _state.value = GameplayUiState.Ended(result, partial)
                val at = now()
                runAt = at
                recordResult(result, partial, at)
                if (!partial && replayTemp != null) {
                    awaitReplay()
                } else {
                    replayJob?.cancel()
                    saveJob?.cancel()
                    setReplaySave(ReplaySave.Unavailable)
                }
            }
            PlayState.PAUSED -> _playUi.value = PlayUi.Paused
            PlayState.COUNTDOWN -> {
                // "3", "2", "1" for a third of a second each over the second.
                val left = playBuffer[PlayState.COUNTDOWN_LEFT]
                _playUi.value = PlayUi.Countdown(kotlin.math.ceil(left * 3f).toInt().coerceIn(1, 3))
            }
            else -> _playUi.value = PlayUi.Running
        }
        return haptics
    }

    private val _timing = MutableStateFlow<TimingStats?>(null)

    /** How the ended run's taps landed; null when it has no timed hits. */
    val timing: StateFlow<TimingStats?> = _timing.asStateFlow()

    private val _offsetApply = MutableStateFlow(OffsetApply.Idle)
    val offsetApply: StateFlow<OffsetApply> = _offsetApply.asStateFlow()

    private val _offsetDialog = MutableStateFlow<OffsetDialog?>(null)

    /** The open Apply dialog; null when closed. */
    val offsetDialog: StateFlow<OffsetDialog?> = _offsetDialog.asStateFlow()

    private var offsetJob: Job? = null

    /** Whether Apply can change anything: the debug settings override replaces the saved offsets. */
    val canEditOffset: Boolean get() = store != null && settingsOverride == null

    /** Opens the Apply dialog with each scope's saved value, unless this run's correction was applied already. */
    fun openOffsetDialog() {
        val stats = _timing.value?.takeIf { it.canApply } ?: return
        val store = store?.takeIf { canEditOffset } ?: return
        if (_offsetApply.value != OffsetApply.Idle) return
        viewModelScope.launch(io) {
            val offsets = try {
                store.inputOffsets(server.linkAddress, level).first()
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the input offsets: $e")
                _message.value = OFFSET_FAILED
                return@launch
            }
            if (_timing.value !== stats) return@launch // a new run started meanwhile
            _offsetDialog.value = OffsetDialog(
                stats.correctionMs,
                OffsetScope.entries.map { OffsetChoice(it, offsets.of(it), suggestedOffset(offsets.of(it), stats.correctionMs)) },
            )
        }
    }

    fun dismissOffsetDialog() {
        _offsetDialog.value = null
    }

    /** Saves the open dialog's suggestion for [scope]; Retry waits for it, and leaving the screen does not cancel it. */
    fun applyOffset(scope: OffsetScope) {
        val choice = _offsetDialog.value?.choices?.firstOrNull { it.scope == scope } ?: return
        val store = store ?: return
        if (!_offsetApply.compareAndSet(OffsetApply.Idle, OffsetApply.Saving)) return
        _offsetDialog.value = null
        offsetJob = CoroutineScope(io).launch {
            try {
                store.saveInputOffset(scope, server.linkAddress, level, choice.suggested)
                inputOffsets = inputOffsets.with(scope, choice.suggested)
                if (scope == OffsetScope.Global) appSettings = appSettings.copy(inputOffset = choice.suggested)
                _offsetApply.value = OffsetApply.Applied
            } catch (e: IOException) {
                Log.w(TAG, "Could not save the input offset: $e")
                _offsetApply.value = OffsetApply.Idle
                _message.value = OFFSET_FAILED
            }
        }
    }

    private val _resultInfo = MutableStateFlow<ResultInfo?>(null)

    /** The best result and "New best" for the [GameplayUiState.Ended] screen; null until the store has answered. */
    val resultInfo: StateFlow<ResultInfo?> = _resultInfo.asStateFlow()

    // A partial run (a quit) is never recorded: the screen shows it with the stored best only.
    private fun recordResult(result: PlayResult, partial: Boolean, at: Long) {
        val store = results
        if (store == null) {
            _resultInfo.value = ResultInfo(null, false)
            if (!partial) historyJob = viewModelScope.launch(io) { appendRun(result, null, at) }
            return
        }
        historyJob = viewModelScope.launch(io) {
            // The history append doesn't depend on the best store working: a broken store must not lose the run.
            val previous = try {
                store.best(server, level)
            } catch (e: IOException) {
                null
            }
            if (!partial) appendRun(result, previous, at)
            val info = try {
                val newBest = !partial && store.record(server, level, result)
                ResultInfo(store.best(server, level), newBest, previous)
            } catch (e: IOException) {
                Log.w(TAG, "Could not read or save the best result: $e")
                ResultInfo(null, false)
            }
            _resultInfo.value = info
        }
    }

    /** Adds the run to the level's history; the best before it seeds a level that has none yet. Never throws IO. */
    private suspend fun appendRun(result: PlayResult, previousBest: PlayResult?, at: Long) {
        val runs = history ?: return
        try {
            val record = RunRecord(at, result, levelData = loadedKeys[ResourceKind.LEVEL_DATA], engine = engineKey(loadedKeys))
            runs.append(LevelKey(server.linkAddress, level), record, seed = previousBest)
        } catch (e: IOException) {
            Log.w(TAG, "Could not save the run: $e")
        }
    }

    // The resource keys of the loaded level (stamped on every run record) and the time the last finished run got.
    private var loadedKeys: Map<ResourceKind, String> = emptyMap()
    private var loadedResources: LoadedResources? = null
    private var runAt = 0L

    private val _replaySave = MutableStateFlow<ReplaySave>(ReplaySave.Unavailable)

    /** The result screen's "Save replay" state for the run that just ended. */
    val replaySave: StateFlow<ReplaySave> = _replaySave.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** A one-off message for a snackbar; cleared by [messageShown]. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    private var replayJob: Job? = null
    private var saveJob: Job? = null

    // The run's history append: the replay can only be linked to the record once it is there.
    private var historyJob: Job? = null

    /** Waits (every 100 ms, up to 5 s) for the native side to finish writing the replay, then shows [ReplaySave.Ready]. */
    private fun awaitReplay() {
        replayJob?.cancel()
        val mine = setReplaySave(ReplaySave.Preparing)
        replayJob = viewModelScope.launch(io) {
            repeat(REPLAY_POLLS) {
                when (runtime.replayStatus()) {
                    ReplayStatus.Ready -> {
                        setReplaySave(ReplaySave.Ready, mine)
                        return@launch
                    }
                    ReplayStatus.Failed -> {
                        failReplay(mine)
                        return@launch
                    }
                    else -> delay(REPLAY_POLL_MS)
                }
            }
            failReplay(mine)
        }
    }

    /** The native write failed or never finished: [ReplaySave.Failed], and its message once (only for the current run). */
    private fun failReplay(generation: Int) = synchronized(replayLock) {
        if (generation == replayGeneration) {
            _replaySave.value = ReplaySave.Failed(REPLAY_FAILED)
            _message.value = REPLAY_FAILED
        }
    }

    // A poll's blocking native call is no cancellation point, so a poll of an older run may still try to write after
    // Retry or a newer end. Every new run bumps the generation and a poll only writes while its own is current.
    private val replayLock = Any()
    private var replayGeneration = 0

    /** Sets [value] and starts a new generation (no [generation]), or sets it only if [generation] is still current. */
    private fun setReplaySave(value: ReplaySave, generation: Int? = null): Int = synchronized(replayLock) {
        if (generation == null) {
            replayGeneration++
            _replaySave.value = value
        } else if (generation == replayGeneration) {
            _replaySave.value = value
        }
        replayGeneration
    }

    /**
     * The result screen's "Save replay": copies the temporary replay (only while it is [ReplaySave.Ready]) into the
     * replay store, links it to the run in the history, and adds the level to the library when it isn't there.
     */
    fun saveReplay() {
        val temp = replayTemp ?: return
        val store = replays ?: return
        if (_replaySave.value != ReplaySave.Ready) return
        val at = runAt
        val resources = loadedResources
        val appended = historyJob
        val mine = synchronized(replayLock) {
            if (_replaySave.value != ReplaySave.Ready) return
            _replaySave.value = ReplaySave.Saving
            replayGeneration
        }
        saveJob = viewModelScope.launch(io) {
            // Once the copy has started it runs to the end (and so does the library add that keeps the replay
            // reachable), so a Retry or Back can't leave a half-linked or unreachable replay behind.
            val saved = withContext(NonCancellable) {
                try {
                    val id = store.save(temp.replay, temp.config)
                    try {
                        appended?.join()
                        val linked = history?.attachReplay(LevelKey(server.linkAddress, level), at, id)
                        if (linked == false) Log.w(TAG, "Replay $id saved, but no run record matched $at to link it to")
                    } catch (e: IOException) {
                        Log.w(TAG, "Could not link the replay to the run: $e")
                    }
                    true
                } catch (e: IOException) {
                    Log.w(TAG, "Could not save the replay: $e")
                    false
                }
            }
            if (!saved) {
                if (!isActive) return@launch
                setReplaySave(ReplaySave.Ready, mine)
                _message.value = REPLAY_FAILED
                return@launch
            }
            setReplaySave(ReplaySave.Saved, mine)
            val add = addToLibrary
            if (add != null && resources != null) {
                withContext(NonCancellable) {
                    try {
                        add(resources)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not add $level to the library: $e")
                        _message.value = "Replay saved, but the level couldn't be added to your library"
                    }
                }
            }
        }
    }

    /** The saved `#HAPTIC` option (the engine's default if unsaved); off when the engine has no such option. */
    val hapticsOn: Boolean
        get() = engineOptions.firstOrNull { it.name == HAPTIC_OPTION }?.let { (savedOptions[it.name] ?: it.def) != 0f } ?: false

    /**
     * The result screen's Retry: ends the finished run's native session and starts Play over on the same surface. The
     * native retry only works on a live run, so the level is stopped and started again (it stays loaded).
     */
    fun retryFromResult() {
        if (_state.value !is GameplayUiState.Ended) return
        if (!isPlay) return
        val summary = lastSummary ?: return
        // A second tap while the restart runs does nothing; pollPlay is paused until the new run has started, so the
        // old run's ENDED (still in playBuffer) cannot bring the result screen back.
        if (!restarting.compareAndSet(false, true)) return
        playBuffer[PlayState.STATE] = PlayState.RUNNING
        _resultInfo.value = null
        replayJob?.cancel()
        saveJob?.cancel()
        setReplaySave(ReplaySave.Unavailable)
        _timing.value = null
        _offsetDialog.value = null
        viewModelScope.launch(work) {
            try {
                offsetJob?.join() // an Apply still saving belongs to the run about to start
                _offsetApply.value = OffsetApply.Idle
                synchronized(surfaceLock) { stopLocked() }
                _state.value = GameplayUiState.Ready(summary)
                startIfReady()?.join()
            } finally {
                restarting.set(false)
            }
        }
    }

    private val restarting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Options screen: saves [value] for the option [name] and shows it. */
    fun setOption(name: String, value: Float) {
        val s = _state.value as? GameplayUiState.Options ?: return
        _state.value = s.copy(values = s.values + (name to value))
        savedOptions = savedOptions + (name to value)
        store?.let { viewModelScope.launch(work) { it.saveOption(engine, name, value) } }
    }

    /** Settings: saves [settings] and shows them. */
    fun setSettings(settings: AppSettings) {
        val s = _state.value as? GameplayUiState.Options ?: return
        inputOffsets = inputOffsets.copy(global = settings.inputOffset)
        _state.value = s.copy(settings = settings, offsets = inputOffsets)
        appSettings = settings
        store?.let { viewModelScope.launch(work) { it.saveSettings(settings) } }
    }

    /** Level options: saves this level's or this server's input offset and shows it. */
    fun setInputOffset(scope: OffsetScope, value: Float) {
        val s = _state.value as? GameplayUiState.Options ?: return
        inputOffsets = inputOffsets.with(scope, value)
        if (scope == OffsetScope.Global) appSettings = appSettings.copy(inputOffset = value)
        _state.value = s.copy(settings = appSettings, offsets = inputOffsets)
        store?.let { viewModelScope.launch(work) { it.saveInputOffset(scope, server.linkAddress, level, value) } }
    }

    /** Level options: saves the [kind] item chosen for this level and shows it. */
    fun setLevelItem(kind: ItemKind, selection: ItemSelection) {
        val s = _state.value as? GameplayUiState.Options ?: return
        _state.value = s.copy(items = s.items.copy(level = s.items.level + (kind to selection)))
        store?.let { viewModelScope.launch(work) { it.saveLevelItem(kind, server.linkAddress, level, selection) } }
    }

    /** Level options: saves the [kind] item chosen for this level's engine and shows it. */
    fun setEngineItem(kind: ItemKind, selection: ItemSelection) {
        val s = _state.value as? GameplayUiState.Options ?: return
        _state.value = s.copy(items = s.items.copy(engine = s.items.engine + (kind to selection)))
        store?.let { viewModelScope.launch(work) { it.saveEngineItem(kind, engine, selection) } }
    }

    /** The saved choices per kind; one naming an item that is no longer saved shows as its scope's default. */
    private suspend fun readItemPickers(): ItemPickerState {
        val saved = savedItems()
        fun shown(selection: ItemSelection, default: ItemSelection) =
            if (selection is ItemSelection.Saved && saved.none { it.id == selection.id }) default else selection
        return ItemPickerState(
            saved = saved,
            level = ItemKind.entries.associateWith { kind ->
                shown(store?.levelItem(kind, server.linkAddress, level)?.first() ?: ItemSelection.UseEngine, ItemSelection.UseEngine)
            },
            engine = ItemKind.entries.associateWith { kind ->
                shown(store?.engineItem(kind, engine)?.first() ?: ItemSelection.Original, ItemSelection.Original)
            },
            engineKnown = engine != "default",
        )
    }

    private suspend fun readPreferences(configuration: File?) {
        engineOptions = try {
            configuration?.takeIf { it.isFile }
                ?.let { EngineOptions.parse(EngineOptions.decode(it.readBytes())) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the engine's options: $e")
            emptyList()
        }
        if (store != null && importOptions != null) {
            val known = engineOptions.map { it.name }.toSet()
            for ((name, value) in importOptions) if (name in known) store.saveOption(engine, name, value)
        }
        savedOptions = store?.options(engine)?.first() ?: emptyMap()
        appSettings = store?.settings?.first() ?: AppSettings()
        inputOffsets = store?.inputOffsets(server.linkAddress, level)?.first() ?: InputOffsets()
    }

    private fun load() {
        job?.cancel()
        synchronized(surfaceLock) { stopLocked() }
        _state.value = GameplayUiState.Loading(null)
        if (optionsOnly) {
            job = viewModelScope.launch { _state.value = loadOptions() }
            return
        }
        job = viewModelScope.launch {
            _state.value = try {
                val resources = fetcher.load(server, level) { _state.value = GameplayUiState.Loading(it) }
                loadedKeys = resources.keys
                loadedResources = resources
                checkReplay(resources)?.let {
                    _state.value = it
                    return@launch
                }
                onLoaded?.let { hook ->
                    viewModelScope.launch(io) {
                        try {
                            hook(resources)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "After-load hook failed for $level: $e")
                        }
                    }
                }
                if (render == null) levelDetails?.let { read ->
                    viewModelScope.launch(io) {
                        try {
                            _levelInfo.value = read(resources)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not read the details of $level: $e")
                        }
                    }
                }
                withContext(work) {
                    ensureActive()
                    val start = System.nanoTime()
                    val result = runtime.load(resources)
                    val ms = (System.nanoTime() - start) / 1_000_000
                    if (result is NativeLoad.Loaded) readPreferences(resources.files[ResourceKind.ENGINE_CONFIGURATION])
                    when (result) {
                        is NativeLoad.Loaded -> {
                            if (render == null) markPlayed()
                            GameplayUiState.Ready(result.summary).also { lastSummary = result.summary }
                        }
                        is NativeLoad.Failed -> GameplayUiState.Failed(NativeError.message(result.text))
                    }.also { Log.i(TAG, "Native load of $level took $ms ms: $it") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoadFailure) {
                GameplayUiState.Failed(failureText(e))
            } catch (e: Exception) {
                GameplayUiState.Failed(e.message ?: "Something went wrong")
            }
            if (_state.value is GameplayUiState.Ready) {
                pendingNote?.let { text ->
                    pendingNote = null
                    _note.value = text
                    viewModelScope.launch {
                        delay(NOTE_MS)
                        if (_note.value == text) _note.value = null
                    }
                }
            }
            if (render != null) renderIfReady(render) else startIfReady()
        }
    }

    // The replay's options once its files checked out, and the note an engine change leaves for the screen.
    private var replayOptions: FloatArray? = null
    private var pendingNote: String? = null

    /**
     * Watch of a saved replay, after the fetch and before the level is loaded into the runtime: the replay's files
     * must be readable (else "This replay can't be read"), then it must match the chart (else it is blocked; an
     * engine change only leaves a note). Returns the state that stops the run, or null to go on.
     */
    private suspend fun checkReplay(resources: LoadedResources): GameplayUiState? {
        replayOptions = null
        pendingNote = null
        val ref = replay ?: return null
        if (isPlay) return null
        val store = replays ?: return GameplayUiState.Stopped(REPLAY_UNREADABLE)
        try {
            if (!withContext(io) { store.replayFile(ref.id).isFile }) throw IOException("missing replay")
            replayOptions = store.options(ref.id)
        } catch (e: IOException) {
            Log.w(TAG, "Could not read replay ${ref.id}: $e")
            return GameplayUiState.Stopped(REPLAY_UNREADABLE)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Not a replay id: ${ref.id}")
            return GameplayUiState.Stopped(REPLAY_UNREADABLE)
        }
        return when (replayMatch(ref.levelData, ref.engine, resources.keys)) {
            ReplayMatch.ChartChanged -> GameplayUiState.Stopped(REPLAY_CHART_CHANGED)
            ReplayMatch.EngineChanged -> {
                pendingNote = REPLAY_ENGINE_CHANGED
                null
            }
            ReplayMatch.Same -> null
        }
    }

    /**
     * The "Level options" screen: reads only the engine configuration (cached, local for a downloaded level), never the
     * level's other files or the native runtime.
     */
    private suspend fun loadOptions(): GameplayUiState = try {
        val configuration = fetcher.engineConfiguration(server, level)
        withContext(work) {
            readPreferences(configuration)
            GameplayUiState.Options(
                engineOptions,
                engineOptions.associate { it.name to (savedOptions[it.name] ?: it.def) },
                appSettings,
                inputOffsets,
                readItemPickers(),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: LoadFailure) {
        GameplayUiState.Failed(failureText(e))
    } catch (e: Exception) {
        GameplayUiState.Failed(e.message ?: "Something went wrong")
    }

    /**
     * Debug render-at-time: renders once loaded, then writes the outcome to [RenderRequest.DONE_FILE] for the PC to
     * poll; a request error or a failed load is written there too, so the PC never waits for nothing.
     */
    private suspend fun renderIfReady(request: RenderRequest) {
        val state = _state.value
        val error = when {
            request.error != null -> request.error
            state is GameplayUiState.Failed -> state.message
            state !is GameplayUiState.Ready -> return
            else -> withContext(work) {
                val start = System.nanoTime()
                runtime.renderAtTimes(request.times, request.options, request.outDir.path).also {
                    val ms = (System.nanoTime() - start) / 1_000_000
                    Log.i(TAG, "Rendered ${request.times.size} times of $level in $ms ms: ${it ?: "ok"}")
                }
            }
        }
        withContext(work) {
            File(request.outDir, RenderRequest.DONE_FILE)
                .writeText(if (error == null) "ok ${request.times.size}\n" else "error: $error\n")
        }
        if (error != null) _state.value = GameplayUiState.Failed(NativeError.message(error))
    }

    override fun onCleared() {
        // The native load cannot be interrupted, and close waits for it; keep that off the main thread.
        // viewModelScope is already cancelled, so a load that finishes now updates nothing.
        thread(name = "gameplay-close") { runtime.close() }
    }

    /** Last played: for the Play list's "Last played" sort. A failure only loses the time stamp. */
    private fun markPlayed() {
        val store = results ?: return
        viewModelScope.launch(io) {
            try {
                store.markPlayed(server, level, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save last played for $level: $e")
            }
        }
    }

    private fun failureText(e: LoadFailure): String {
        if (e.damaged) return "This download is damaged. Delete the level and download it again."
        val reason = when (val cause = e.cause) {
            is IOException if (cause.isDiskFull()) -> "Not enough storage. Delete some levels and try again."
            is MissingResource -> "The level details don't include it"
            is HashMismatch -> "The download was corrupted"
            is ServerApiException -> "The server returned an error (${cause.code})"
            is SerializationException -> "The server sent data this app can't read"
            is IOException -> "Couldn't reach the server"
            else -> cause?.message ?: "Something went wrong"
        }
        return e.kind?.let { "${it.wireName}: $reason" } ?: reason
    }

    private companion object {
        const val TAG = "Gameplay"
        const val HAPTIC_OPTION = "#HAPTIC"
        const val REPLAY_POLL_MS = 100L
        const val REPLAY_POLLS = 50 // 5 s
        const val REPLAY_FAILED = "Couldn't save the replay"
        const val OFFSET_FAILED = "Couldn't save the input offset"
        const val REPLAY_UNREADABLE = "This replay can't be read"
        const val REPLAY_CHART_CHANGED = "This replay was recorded on an older version of this chart"
        const val REPLAY_ENGINE_CHANGED = "The engine was updated since this run; playback may not match exactly"
        const val NOTE_MS = 5_000L
        val ARCHETYPE = Regex("archetype '([^']*)'")
    }
}
