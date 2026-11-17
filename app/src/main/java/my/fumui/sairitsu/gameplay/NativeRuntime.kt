package my.fumui.sairitsu.gameplay

import android.view.Surface
import java.io.File

/** JNI surface of `libsairitsu_runtime.so` (runtime/src/android/jni_bridge.cpp). */
object NativeRuntime {
    init {
        System.loadLibrary("sairitsu_runtime")
    }

    external fun create(): Long

    /** Parses the files ([kinds] are [ResourceKind] codes). Null when loaded, else error text (see [NativeError]). */
    external fun load(handle: Long, kinds: IntArray, paths: Array<String>): String?

    /** A one-line summary of the loaded level, e.g. "97 archetypes, 1080 entities". */
    external fun describe(handle: Long): String

    /**
     * Runs the loaded level on [surface] in [mode] (a [GameplayMode] ordinal; only Watch for now) on a native render
     * thread, stopping a running one first. [options] are Level Option values in the engine configuration's order
     * (fewer take the defaults); [settings] are [NativeSettings] values. Returns once the level is prepared and the
     * renderer set up: null when it runs, else error text (see [NativeError]).
     *
     * Play: a run that ends on its own writes its replay to `[replayOut].json.gz` and `[replayOut].config.gz` (a base
     * path; null records nothing; the directory must exist) in the background; [replayStatus] tells when they are
     * ready. Watch: [replayIn] (a saved `.json.gz`) is played back instead of the chart's autoplay; an unreadable one
     * fails the start with text starting "replay:". Every start resets [replayStatus] to none.
     */
    external fun start(
        handle: Long,
        surface: Surface,
        mode: Int,
        options: FloatArray,
        settings: FloatArray,
        replayOut: String?,
        replayIn: String?,
    ): String?

    /**
     * Play: the last run's replay files, a ReplayStatus code: 0 none (no run ended yet, a retry, a quit), 1 writing,
     * 2 ready, 3 failed. Cheap; never waits on the write.
     */
    external fun replayStatus(handle: Long): Int

    /** Stops a running level and releases its surface; waits for the render thread. No-op when none runs. */
    external fun stop(handle: Long)

    /**
     * Debug builds: renders the loaded level in Watch with Level Option values [options] (as for
     * [start]) at each of [times] (music time, s) into an
     * offscreen 3200 x 2136 frame and writes `<index>_<time>.png` into [outDir], on the calling thread; no audio, no
     * surface. A fresh session steps a 1/120 s grid and lands exactly on each time. Null when done, else error text.
     */
    external fun renderAtTimes(handle: Long, times: FloatArray, options: FloatArray, outDir: String): String?

    /**
     * What the Compose layer draws over the running level (the geometry is render/ui.cpp's): the built-in HUD widgets
     * the engine shows and, in Watch, the control bar. Fills [out] (at least [NativeOverlay.FLOATS] long, layout
     * in [NativeOverlay]) and returns the texts joined by '\n', or null when there is nothing to draw.
     */
    external fun overlay(handle: Long, out: FloatArray): String?

    /** The render thread's frames per second over the last half second; 0 while nothing runs. Cheap, any thread. */
    external fun renderFps(handle: Long): Float

    /** "kind\ndetail\nmusic time" of the callback fault that stopped the running level, or null while it runs. */
    external fun fault(handle: Long): String?

    /**
     * Play: [count] pointers of [data] ([TouchPhase] codes; per pointer id, phase, x px, y px in surface pixels) at
     * CLOCK_MONOTONIC [timeNanos]. Never takes any lock `start` holds; dropped while no Play runs or after it ended.
     */
    external fun touch(handle: Long, timeNanos: Long, count: Int, data: FloatArray)

    /** Fills [out] ([PlayState.FLOATS]: the state, then the result); returns the pending haptic levels (drained). */
    external fun playState(handle: Long, out: FloatArray): IntArray

    /**
     * Play: freezes the stage on its last frame, cuts all audio and drops touches. Cheap and lock-free from any
     * thread; [playState] reads the new state at once.
     */
    external fun pause(handle: Long)

    /** Play: runs the 1.0 s silent countdown over the frozen stage, then continues from the paused time. */
    external fun resume(handle: Long)

    /** Play: back to -2 s at once, no countdown. */
    external fun retry(handle: Long)

    /** Play: ends the run with the stats so far ([PlayState.PARTIAL]). */
    external fun quit(handle: Long)

    /**
     * Play: the engine's Menu widget box in screen units (x in -aspect..aspect, y up in -1..1) into [out] (4 floats:
     * left, bottom, right, top); false while the engine has not set it.
     */
    external fun menuRect(handle: Long, out: FloatArray): Boolean

    /**
     * Play, in the background: pauses and lets go of the surface so the system can destroy it, keeping the run.
     * True when it did; false means the caller must [stop] and start over.
     */
    external fun detachSurface(handle: Long): Boolean

    /** Play: takes a new surface of the same size after [detachSurface] and shows the paused frame on it. */
    external fun attachSurface(handle: Long, surface: Surface): Boolean

    /**
     * Watch: true freezes the stage and the audio, false continues at once. Cheap, any thread (the Watch controls
     * below are all like this; nothing in Play). Each of them shows the bar again.
     */
    external fun watchPause(handle: Long, paused: Boolean)

    /** Watch: jumps to music time [time]; [scrubbing] keeps the audio paused until the release (a seek with false). */
    external fun seek(handle: Long, time: Float, scrubbing: Boolean)

    /** Watch: loops at the end (the default) or stops there. */
    external fun setRepeat(handle: Long, on: Boolean)

    /** Watch: shows the bar for another 3 s. */
    external fun showBar(handle: Long)

    /** Watch: fills [out] ([WatchState.FLOATS]); false (and zeros) in Play or while nothing runs. */
    external fun watchState(handle: Long, out: FloatArray): Boolean

    external fun destroy(handle: Long)
}

/**
 * Layout of [NativeRuntime.watchState]'s float array. The track and the two rects are left, bottom, right, top in
 * screen units (x in -aspect..aspect, y up in -1..1).
 */
object WatchState {
    const val TIME = 0
    const val LENGTH = 1
    const val PAUSED = 2
    const val REPEAT = 3
    const val VISIBLE = 4
    const val TRACK = 5
    const val REPEAT_RECT = 9
    const val PAUSE_RECT = 13
    const val FLOATS = 17
}

/** Layout of [NativeRuntime.playState]'s float array. */
object PlayState {
    const val STATE = 0
    const val RUNNING = 0f
    const val PAUSED = 1f
    const val COUNTDOWN = 2f
    const val ENDED = 3f

    /** While [COUNTDOWN]: the seconds left, 1 down to 0. */
    const val COUNTDOWN_LEFT = 1

    /** Index of the result: grade (0 all perfect, 1 full combo, 2 pass, 3 fail), arcade, accuracy, combo, P, G, Good, M, total. */
    const val RESULT = 1

    /** Once ended: 1 when the player quit (the stats so far), 0 when the run ended. */
    const val PARTIAL = 10
    const val FLOATS = 11
}

/**
 * Layout of [NativeRuntime.overlay]'s float array (jni_bridge.cpp mirrors it). Screen units: x in -aspect..aspect,
 * y up in -1..1. After the header come the rects, then the texts, then the icons.
 */
object NativeOverlay {
    const val RECTS = 0 // the rect count
    const val TEXTS = 1 // the text count (the returned string's lines, in order)
    const val ICONS = 2 // the icon count
    const val HEADER = 4
    const val PER_RECT = 12 // 4 x, 4 y, r, g, b, a
    const val PER_TEXT = 11 // x, y (vertical centre), size (the em), pivot, r, g, b, a, bold, outline, 0
    const val PER_ICON = 13 // kind (render::UiIconKind), 4 x, 4 y (bl, tl, tr, br), r, g, b, a
    const val MAX_RECTS = 320 // dense replays keep their Miss/Good ticks; jni_bridge.cpp's kMaxRects matches
    const val MAX_TEXTS = 16
    const val MAX_ICONS = 16
    const val FLOATS = HEADER + MAX_RECTS * PER_RECT + MAX_TEXTS * PER_TEXT + MAX_ICONS * PER_ICON
}

/** Layout of [NativeRuntime.start]'s settings array, in AppSettings order. Entries past the end are 0. */
object NativeSettings {
    const val EFFECT_VOLUME = 0
    const val BGM_VOLUME = 1
    const val AUDIO_OFFSET = 2 // seconds, into Runtime Environment
    const val INPUT_OFFSET = 3 // seconds, into Runtime Environment

    /** The defaults: see [AppSettings]. */
    fun defaults(): FloatArray = AppSettings().toNative()
}

/** Error text protocol of [NativeRuntime.load]; kept apart from [NativeRuntime] so it needs no library. */
object NativeError {
    /**
     * Prefix before the name of an engine function or block the runtime doesn't know. Matches
     * `kUnsupportedPrefix` in jni_bridge.cpp. Nothing emits it until the interpreter lands.
     */
    const val UNSUPPORTED = "unsupported:"

    /** Prefix before a callback fault's "kind: detail" (archetype and callback in it). Matches `kFaultPrefix`. */
    const val FAULT = "fault:"

    /** The text to show for an error [text] from [NativeRuntime.load] or [NativeRuntime.start]. */
    fun message(text: String): String = when {
        text.startsWith(UNSUPPORTED) ->
            "This level needs %s, which this app doesn't support".format(text.removePrefix(UNSUPPORTED))
        text.startsWith(FAULT) -> faultMessage(text.removePrefix(FAULT))
        else -> text
    }

    fun faultMessage(reason: String): String = "The level stopped: %s".format(reason)

    fun isFault(text: String): Boolean = text.startsWith(FAULT)
}

/** Outcome of [LevelRuntime.load]. */
sealed interface NativeLoad {
    data class Loaded(val summary: String) : NativeLoad

    /** [text] is the native error text (see [NativeError]). */
    data class Failed(val text: String) : NativeLoad
}

/** The native side of a level: parses its files and summarises it. One instance per screen. */
interface LevelRuntime {
    /**
     * Parses the files and, when that works, summarises the level, as one step that [close] cannot
     * split. Throws [IllegalStateException] once closed.
     */
    fun load(resources: LoadedResources): NativeLoad

    /**
     * Runs the loaded level on [surface] (see [NativeRuntime.start]), stopping a running one first. Null when it
     * runs, else error text. Throws [IllegalStateException] once closed.
     */
    fun start(
        surface: Surface,
        mode: GameplayMode,
        options: FloatArray,
        settings: FloatArray,
        replayOut: File? = null,
        replayIn: File? = null,
    ): String?

    /** Play: whether the replay of the run that ended is being written, ready (both files) or failed. */
    fun replayStatus(): ReplayStatus = ReplayStatus.None

    /** Stops a running level; waits for its render thread. No-op when none runs or once closed. */
    fun stop()

    /** Debug render-at-time (see [NativeRuntime.renderAtTimes]). Null when done, else error text. */
    fun renderAtTimes(times: FloatArray, options: FloatArray, outDir: String): String? =
        "render-at-time isn't available"

    /** The HUD and Watch's bar for the running level (see [NativeRuntime.overlay]); null when nothing shows. */
    fun overlay(out: FloatArray): String? = null

    /** The render thread's frames per second (see [NativeRuntime.renderFps]); 0 while nothing runs. */
    fun renderFps(): Float = 0f

    /** The fault that stopped the running level ("kind\ndetail\ntime"), or null. */
    fun fault(): String? = null

    /** Play: forwards pointers (see [NativeRuntime.touch]). Must not wait for [start]. */
    fun touch(timeNanos: Long, count: Int, data: FloatArray) = Unit

    /** Play: fills [out] (see [NativeRuntime.playState]) and returns the pending haptic levels. */
    fun playState(out: FloatArray): IntArray = IntArray(0)

    /** Play: pause, resume (the countdown), retry and quit. Like [touch], they must not wait for [start]. */
    fun pause() = Unit

    fun resume() = Unit

    fun retry() = Unit

    fun quit() = Unit

    /** Play: the engine's Menu widget box (see [NativeRuntime.menuRect]); false when unknown. */
    fun menuRect(out: FloatArray): Boolean = false

    /** Play, in the background: let go of the surface keeping the run (see [NativeRuntime.detachSurface]). */
    fun detachSurface(): Boolean = false

    /** Play: show the kept run on a new surface of the same size. False: stop and start over. */
    fun attachSurface(surface: Surface): Boolean = false

    /** Watch's controls (see [NativeRuntime.watchPause] and the ones after it). Like [touch], they must not wait for [start]. */
    fun watchPause(paused: Boolean) = Unit

    fun seek(time: Float, scrubbing: Boolean) = Unit

    fun setRepeat(on: Boolean) = Unit

    fun showBar() = Unit

    /** Watch: fills [out] (see [WatchState]); false when nothing runs. */
    fun watchState(out: FloatArray): Boolean = false

    /** Frees the native side; waits for a [load] in progress. Later calls are no-ops. */
    fun close()
}

/** [LevelRuntime] backed by one native handle. */
class NativeLevelRuntime : LevelRuntime {
    private var handle = NativeRuntime.create()

    @Synchronized
    override fun load(resources: LoadedResources): NativeLoad {
        check(handle != 0L) { "closed" }
        val entries = resources.files.entries.toList()
        val error = NativeRuntime.load(
            handle,
            IntArray(entries.size) { entries[it].key.ordinal },
            Array(entries.size) { entries[it].value.absolutePath },
        )
        return if (error == null) NativeLoad.Loaded(NativeRuntime.describe(handle)) else NativeLoad.Failed(error)
    }

    @Synchronized
    override fun start(
        surface: Surface,
        mode: GameplayMode,
        options: FloatArray,
        settings: FloatArray,
        replayOut: File?,
        replayIn: File?,
    ): String? {
        check(handle != 0L) { "closed" }
        return NativeRuntime.start(
            handle, surface, mode.ordinal, options, settings, replayOut?.absolutePath, replayIn?.absolutePath,
        )
    }

    // Like fault: not @Synchronized, the UI thread polls it while start() may hold the lock.
    override fun replayStatus(): ReplayStatus =
        handle.takeIf { it != 0L }?.let { ReplayStatus.fromCode(NativeRuntime.replayStatus(it)) } ?: ReplayStatus.None

    @Synchronized
    override fun stop() {
        if (handle != 0L) NativeRuntime.stop(handle)
    }

    @Synchronized
    override fun renderAtTimes(times: FloatArray, options: FloatArray, outDir: String): String? {
        check(handle != 0L) { "closed" }
        return NativeRuntime.renderAtTimes(handle, times, options, outDir)
    }

    // Not @Synchronized: start() holds the lock for seconds, and the UI thread polls these. The native side locks its
    // own loop pointer; handle is a plain read (0 once closed, which the native side treats as no runtime).
    override fun overlay(out: FloatArray): String? = handle.takeIf { it != 0L }?.let { NativeRuntime.overlay(it, out) }

    override fun renderFps(): Float = handle.takeIf { it != 0L }?.let { NativeRuntime.renderFps(it) } ?: 0f

    override fun fault(): String? = handle.takeIf { it != 0L }?.let { NativeRuntime.fault(it) }

    override fun touch(timeNanos: Long, count: Int, data: FloatArray) {
        handle.takeIf { it != 0L }?.let { NativeRuntime.touch(it, timeNanos, count, data) }
    }

    override fun playState(out: FloatArray): IntArray =
        handle.takeIf { it != 0L }?.let { NativeRuntime.playState(it, out) } ?: IntArray(0)

    // Like touch: not @Synchronized. The native side takes only its loop pointer's short lock.
    override fun pause() {
        handle.takeIf { it != 0L }?.let { NativeRuntime.pause(it) }
    }

    override fun resume() {
        handle.takeIf { it != 0L }?.let { NativeRuntime.resume(it) }
    }

    override fun retry() {
        handle.takeIf { it != 0L }?.let { NativeRuntime.retry(it) }
    }

    override fun quit() {
        handle.takeIf { it != 0L }?.let { NativeRuntime.quit(it) }
    }

    override fun menuRect(out: FloatArray): Boolean =
        handle.takeIf { it != 0L }?.let { NativeRuntime.menuRect(it, out) } ?: false

    override fun watchPause(paused: Boolean) {
        handle.takeIf { it != 0L }?.let { NativeRuntime.watchPause(it, paused) }
    }

    override fun seek(time: Float, scrubbing: Boolean) {
        handle.takeIf { it != 0L }?.let { NativeRuntime.seek(it, time, scrubbing) }
    }

    override fun setRepeat(on: Boolean) {
        handle.takeIf { it != 0L }?.let { NativeRuntime.setRepeat(it, on) }
    }

    override fun showBar() {
        handle.takeIf { it != 0L }?.let { NativeRuntime.showBar(it) }
    }

    override fun watchState(out: FloatArray): Boolean =
        handle.takeIf { it != 0L }?.let { NativeRuntime.watchState(it, out) } ?: false

    @Synchronized
    override fun detachSurface(): Boolean = handle != 0L && NativeRuntime.detachSurface(handle)

    @Synchronized
    override fun attachSurface(surface: Surface): Boolean = handle != 0L && NativeRuntime.attachSurface(handle, surface)

    @Synchronized
    override fun close() {
        if (handle != 0L) NativeRuntime.destroy(handle)
        handle = 0L
    }
}
