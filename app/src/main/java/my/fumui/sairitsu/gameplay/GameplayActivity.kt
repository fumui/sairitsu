package my.fumui.sairitsu.gameplay

import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import my.fumui.sairitsu.play.GameplayFeatures
import my.fumui.sairitsu.ui.SairitsuTheme
import my.fumui.sairitsu.app.AppServices
import my.fumui.sairitsu.app.saveIfWanted
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Loads a level and runs it (Watch) on a SurfaceView. Landscape, immersive, screen kept on. */
class GameplayActivity : ComponentActivity() {
    private val request by lazy {
        GameplayIntent.parse { intent.getStringExtra(it) }
    }

    /** `offline` is honoured only through the debug-only DebugGameplayEntry alias. */
    private val offline by lazy {
        intent.component?.className == DEBUG_ENTRY && intent.getBooleanExtra(GameplayIntent.OFFLINE, false)
    }

    /**
     * Debug-only render-at-time (`renderTimes`: a file of music times, `outDir`: where the PNGs go, optional
     * `renderOptions`: a file of Level Option values), honoured only through DebugGameplayEntry (debug manifest) and
     * only in a debuggable build. Unreadable files give a request that only reports the error.
     */
    private val render: RenderRequest? by lazy {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (intent.component?.className != DEBUG_ENTRY || !debuggable) return@lazy null
        val times = intent.getStringExtra(GameplayIntent.RENDER_TIMES) ?: return@lazy null
        val outDir = File(intent.getStringExtra(GameplayIntent.OUT_DIR) ?: return@lazy null).apply { mkdirs() }
        val options = intent.getStringExtra(GameplayIntent.RENDER_OPTIONS)
        try {
            RenderRequest(
                RenderRequest.parseValues(File(times).readText()),
                outDir,
                options?.let { RenderRequest.parseValues(File(it).readText()) } ?: FloatArray(0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable render input: $e")
            RenderRequest(FloatArray(0), outDir, FloatArray(0), error = "unreadable render input: $e")
        }
    }

    private val debugEntry by lazy {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        debuggable && intent.component?.className == DEBUG_ENTRY
    }

    /**
     * Debug-only `effectVolume`, `bgmVolume` and `audioOffset` extras (comparison runs), as for `offline`; they replace
     * the saved settings. Null: the saved settings.
     */
    private val settings by lazy {
        if (debugEntry && GameplayIntent.hasSettings { intent.getStringExtra(it) }) {
            GameplayIntent.settings { intent.getStringExtra(it) }
        } else {
            null
        }
    }

    /** Debug-only `replayConfig` (a file): a replay `config`, saved as this engine's Level Options. */
    private val importOptions by lazy {
        val path = if (debugEntry) intent.getStringExtra(GameplayIntent.REPLAY_CONFIG) else null
        path?.let {
            try {
                ReplayConfig.parse(EngineOptions.decode(File(it).readBytes())).byName()
            } catch (e: Exception) {
                Log.w(TAG, "Unreadable replay config: $e")
                null
            }
        }
    }

    private val vm: GameplayViewModel by viewModels {
        viewModelFactory {
            initializer {
                val req = request!!
                val services = AppServices.get(applicationContext)
                val loader = if (offline) services.debugOfflineLoader else services.loader
                val fetcher = object : LevelFetcher {
                    override suspend fun load(
                        server: ServerAddress,
                        level: String,
                        onProgress: (LoadProgress) -> Unit,
                    ): LoadedResources {
                        val loaded = loader.load(server, level, onProgress)
                        if (render != null) return loaded
                        return withContext(Dispatchers.IO) {
                            services.skins.apply(loaded, server.linkAddress, level, services.library.engineNameOf(loaded))
                        }
                    }

                    override suspend fun engineConfiguration(server: ServerAddress, level: String) =
                        loader.engineConfiguration(server, level)
                }
                GameplayViewModel(
                    req.server, req.level, fetcher, NativeLevelRuntime(), mode = req.mode,
                    render = render, settingsOverride = settings,
                    store = services.settings, engine = req.engine ?: "default",
                    results = services.results,
                    history = services.runs,
                    optionsOnly = req.optionsScreen,
                    faultDir = if (debugEntry || isDebuggable()) File(filesDir, "faults") else null,
                    importOptions = importOptions,
                    onLoaded = if (offline || render != null) null else { loaded -> saveLevelIfWanted(services, req, loaded) },
                    levelDetails = if (render != null) null else { loaded -> services.library.playedItem(req.server, loaded) },
                    replayTemp = if (req.mode == GameplayMode.Play && render == null) {
                        File(cacheDir, "replay").mkdirs()
                        ReplayTemp(File(cacheDir, "replay/last.json.gz"), File(cacheDir, "replay/last.config.gz"))
                    } else {
                        null
                    },
                    replays = services.replays,
                    addToLibrary = { loaded -> addToLibrary(services, req, loaded) },
                    replay = req.replay,
                    savedSkins = { services.skinLibrary.skins.value },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = request
        // Play stays hidden behind PLAY_ENABLED; the debug entry (adb) may start it.
        if (req == null || (req.mode == GameplayMode.Play && !GameplayFeatures.PLAY_ENABLED && !debugEntry)) {
            Log.w(TAG, "Missing, invalid or unavailable request; finishing")
            finish()
            return
        }
        Log.i(TAG, "Starting ${req.mode.wire} ${req.level} on ${req.server.baseUrl} offline=$offline" +
            (render?.let { " render ${it.times.size} times into ${it.outDir}" } ?: ""))
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // The level draws on the SurfaceView (native render thread); the Compose overlay above it shows loading and
        // errors and is see-through while the level runs.
        val surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(surfaceCallback)
        }
        val overlay = ComposeView(this).apply {
            setContent {
                SairitsuTheme {
                    GameplayScreen(vm, onBack = ::finish)
                }
            }
        }
        setContentView(
            FrameLayout(this).apply {
                addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            },
        )
    }

    // Every pointer event goes to the level first (Play only; the view model ignores them in Watch). The native side
    // queues them, so this never waits for the level to start.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (request != null) vm.touch(MotionEventSource(ev))
        return super.dispatchTouchEvent(ev)
    }

    /** Going to the background pauses Play; coming back shows the pause menu, not an automatic resume. */
    override fun onPause() {
        super.onPause()
        if (request?.mode == GameplayMode.Play) vm.pause()
    }

    // Leaving the screen (home, another app, screen off) does not finish either mode. The system takes the surface and
    // the surface callbacks stop the level: a Play is kept paused (it comes back with the pause menu up), a Watch
    // remembers its time and pause and the view model restores them when the surface comes back.

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(TAG, "Surface ${width}x$height")
            vm.surfaceChanged(holder.surface, width, height)
        }

        // Returns only once the render thread has let go of the surface.
        override fun surfaceDestroyed(holder: SurfaceHolder) = vm.surfaceDestroyed()
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** "Save levels I play": adds a level loaded from its server to the library (the setting is read now). */
    private suspend fun saveLevelIfWanted(services: AppServices, req: GameplayRequest, loaded: LoadedResources) {
        val key = LevelKey(req.server.linkAddress, req.level)
        saveIfWanted(services.settings.saveLevelsIPlay.first(), services.library.index.get(key) != null) {
            val title = services.servers.servers.first().firstOrNull { it.address == key.server }?.title ?: key.server
            services.downloader.saveLoaded(req.server, title, loaded)
            Log.i(TAG, "Saved ${req.level} to the library")
        }
    }

    /** Saving a replay keeps its level: adds it to the library when it isn't there (throws on failure). */
    private suspend fun addToLibrary(services: AppServices, req: GameplayRequest, loaded: LoadedResources) {
        val key = LevelKey(req.server.linkAddress, req.level)
        if (services.library.index.get(key) != null) return
        val title = services.servers.servers.first().firstOrNull { it.address == key.server }?.title ?: key.server
        services.downloader.saveLoaded(req.server, title, loaded)
        Log.i(TAG, "Added ${req.level} to the library for a saved replay")
    }

    private companion object {
        const val TAG = "Gameplay"
        const val DEBUG_ENTRY = "my.fumui.sairitsu.gameplay.DebugGameplayEntry"
    }
}

