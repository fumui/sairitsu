package my.fumui.sairitsu.app

import android.content.Context
import my.fumui.sairitsu.data.CachingLevelSource
import my.fumui.sairitsu.data.LevelCatalog
import my.fumui.sairitsu.data.LevelSource
import my.fumui.sairitsu.ui.CatalogHandle
import my.fumui.sairitsu.data.ResponseCache
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.ServerConfigInterceptor
import my.fumui.sairitsu.data.ServerConfigStore
import my.fumui.sairitsu.data.ServerListStore
import my.fumui.sairitsu.data.ServerApi
import my.fumui.sairitsu.gameplay.DataStoreResultStore
import my.fumui.sairitsu.gameplay.FileRunHistory
import my.fumui.sairitsu.gameplay.ReplayStore
import my.fumui.sairitsu.gameplay.RunHistory
import my.fumui.sairitsu.gameplay.LevelLoader
import my.fumui.sairitsu.gameplay.ResourceCache
import my.fumui.sairitsu.gameplay.ResultStore
import my.fumui.sairitsu.gameplay.ScopedItems
import my.fumui.sairitsu.gameplay.SettingsStore
import my.fumui.sairitsu.gameplay.ItemLibrary
import my.fumui.sairitsu.gameplay.ItemPictures
import my.fumui.sairitsu.gameplay.ItemMigration
import my.fumui.sairitsu.library.CollectionStore
import my.fumui.sairitsu.library.Library
import my.fumui.sairitsu.library.DownloadService
import my.fumui.sairitsu.library.DownloadState
import my.fumui.sairitsu.library.LibraryDownloader
import my.fumui.sairitsu.library.LibraryIndex
import my.fumui.sairitsu.library.LibraryQueryStore
import my.fumui.sairitsu.library.PlaylistSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * "Save levels I play": runs [save] (and returns true) only when the setting is on and the level is not already in
 * the library.
 */
suspend fun saveIfWanted(enabled: Boolean, inLibrary: Boolean, save: suspend () -> Unit): Boolean {
    if (!enabled || inLibrary) return false
    save()
    return true
}

/**
 * Process-wide services shared by the picker screens and [my.fumui.sairitsu.gameplay.GameplayActivity]. One
 * [ResourceCache] may own its directory at a time, so there is exactly one instance per process.
 */
class AppServices private constructor(context: Context) {
    val serverConfigs = ServerConfigStore(File(context.filesDir, "server-config.json"))

    val client = OkHttpClient.Builder().addInterceptor(ServerConfigInterceptor { serverConfigs.values.value }).build()

    /** filesDir, not cacheDir: the OS may clear cacheDir, and a downloaded level must start offline. */
    val cache = ResourceCache(File(context.filesDir, "resources"))
    val library = Library(LibraryIndex(File(context.filesDir, "library.json")), cache)

    /** After [library]: loading prunes members the (already loaded) library index doesn't have. */
    val collections = CollectionStore(File(context.filesDir, "collections.json")) {
        library.index.entries.value.mapTo(HashSet()) { it.key }
    }
    val loader = LevelLoader(client, cache, library.index::pinnedKeys, library::local)

    /** Fails every request, standing in for no network (turning Wi-Fi off would drop wireless adb). Debug only. */
    val debugOfflineLoader by lazy {
        val offline = client.newBuilder().addInterceptor { throw IOException("offline (debug)") }.build()
        LevelLoader(offline, cache, library.index::pinnedKeys, library::local)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val downloader = LibraryDownloader(loader, library, scope)

    init {
        // Downloads only start from a tap in the app, so the app is in the foreground when this starts the service.
        scope.launch {
            downloader.states.map { states -> states.values.any { it !is DownloadState.Failed } }
                .distinctUntilChanged()
                .collect { active -> if (active) DownloadService.start(context) }
        }
    }

    /** "Download all as collection" for a server's playlists. */
    val playlistSaver = PlaylistSaver(
        collections,
        download = { server, serverTitle, level -> downloader.download(server, serverTitle, level) },
        libraryKeys = { library.index.entries.value.mapTo(HashSet()) { it.key } },
    )

    init {
        // Pending memberships (ticked in the browser before the download) join once the level is in the library. The
        // flows only trigger a pass: combine's snapshots can be out of order, so settlePending reads them live.
        scope.launch {
            combine(library.index.entries, downloader.states, collections.pending) { _, _, _ -> }.collect {
                try {
                    collections.settlePending(
                        activeDownloads = { downloader.states.value.keys },
                        libraryKeys = { library.index.entries.value.mapTo(HashSet()) { it.key } },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The write failed (disk full): the entries stay pending and the next pass retries.
                }
            }
        }
    }
    val servers = ServerListStore(context)
    val settings = SettingsStore(context)
    val itemLibrary = ItemLibrary(File(context.filesDir, "skins"), client)
    val items = ScopedItems(itemLibrary, settings)
    val itemPictures = ItemPictures(itemLibrary)

    init {
        // Saved skin choices also apply to the background and particles migrated from the skin; retried next start on failure.
        scope.launch {
            try {
                val legacyPending = File(context.filesDir, "skins/skins.json").exists()
                ItemMigration.migrateChoices(settings, itemLibrary.items.value, legacyPending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The flag stays unset, so the next start retries.
            }
        }
    }
    val results: ResultStore = DataStoreResultStore(context)
    val runs: RunHistory = FileRunHistory(File(context.filesDir, "runs"))
    val replays = ReplayStore(File(context.filesDir, "replays"))
    val libraryQuery = LibraryQueryStore(context)

    /** Recent server responses, so reopening a server or level is instant. */
    val responses = ResponseCache()

    /** The client for a server's browse API, with responses cached in [responses]. */
    fun levelSource(server: ServerAddress): LevelSource =
        CachingLevelSource(ServerApi(server, client), responses, server.linkAddress)

    /**
     * Each server's level list saved on the device and synced in the background. It talks to the
     * server directly: sync must see fresh pages, and a full download would flood [responses].
     */
    val catalog = LevelCatalog(File(context.filesDir, "catalogs"), { ServerApi(it, client) }, scope)

    /** The browser's view of [server]'s saved list. */
    fun catalogHandle(server: ServerAddress) = object : CatalogHandle {
        override val state = catalog.state(server)

        override fun open() = catalog.open(server)

        override fun refresh() = catalog.refresh(server)
    }

    companion object {
        private var instance: AppServices? = null

        @Synchronized
        fun get(context: Context): AppServices =
            instance ?: AppServices(context.applicationContext).also { instance = it }
    }
}
