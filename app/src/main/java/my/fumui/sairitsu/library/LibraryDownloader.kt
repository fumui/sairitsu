package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.ServerApiException
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.Srl
import my.fumui.sairitsu.gameplay.HashMismatch
import my.fumui.sairitsu.gameplay.LoadFailure
import my.fumui.sairitsu.gameplay.LoadProgress
import my.fumui.sairitsu.gameplay.LoadedResources
import my.fumui.sairitsu.gameplay.isDiskFull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/** Where a level's files come from: [my.fumui.sairitsu.gameplay.LevelLoader] in the app. */
interface DownloadSource {
    suspend fun load(server: ServerAddress, level: String, onProgress: (LoadProgress) -> Unit): LoadedResources

    suspend fun fetchSrl(server: ServerAddress, srl: Srl?): String?
}

sealed interface DownloadState {
    data object Queued : DownloadState

    data class Running(val progress: LoadProgress?) : DownloadState

    /** [resource]: the file that failed when known, e.g. `engine.playData` or `level.preview`. */
    data class Failed(val message: String, val resource: String?) : DownloadState
}

/** Fetching a level's cover or preview failed; [resource] is `level.cover` or `level.preview`. */
class ExtraResourceFailure(val resource: String, cause: Throwable) : Exception(cause.message, cause)

/**
 * Downloads levels into the [library], [parallel] at a time (the rest wait as [DownloadState.Queued]). A level is in [states]
 * while queued, running or failed; once downloaded it is in the library index instead.
 */
class LibraryDownloader(
    private val source: DownloadSource,
    private val library: Library,
    private val scope: CoroutineScope,
    private val parallel: Int = PARALLEL,
) {
    /** At most [parallel] levels download at once; the rest wait here as Queued. */
    private val slots = Semaphore(parallel)
    private val jobs = HashMap<LevelKey, Job>()

    /** The title of each level in [states], for the download notification. Guarded by `this`. */
    private val titles = HashMap<LevelKey, String>()
    private val _states = MutableStateFlow<Map<LevelKey, DownloadState>>(emptyMap())
    val states: StateFlow<Map<LevelKey, DownloadState>> = _states.asStateFlow()

    /** Queues [level]; null (nothing started) when it is already queued or running. */
    @Synchronized
    fun download(server: ServerAddress, serverTitle: String, level: LevelItem): Job? {
        val key = LevelKey(server.linkAddress, level.name)
        if (jobs[key]?.isActive == true) return null
        titles[key] = level.title
        set(key, DownloadState.Queued)
        return start(server, serverTitle, level, key)
    }

    /**
     * Queues every level in [levels] that isn't queued or running already, marking them all Queued in one state update
     * (so a long list doesn't copy the state map once per level). Returns how many were queued.
     */
    @Synchronized
    fun downloadAll(server: ServerAddress, serverTitle: String, levels: List<LevelItem>): Int {
        val todo = levels.distinctBy { it.name }
            .map { LevelKey(server.linkAddress, it.name) to it }
            .filter { (key, _) -> jobs[key]?.isActive != true }
        if (todo.isEmpty()) return 0
        for ((key, level) in todo) titles[key] = level.title
        _states.update { states -> states + todo.map { (key, _) -> key to DownloadState.Queued } }
        for ((key, level) in todo) start(server, serverTitle, level, key)
        return todo.size
    }

    /** Launches the download of [level]; its state is already Queued. Caller holds the lock. */
    private fun start(server: ServerAddress, serverTitle: String, level: LevelItem, key: LevelKey): Job {
        val job = scope.launch {
            try {
                slots.withPermit {
                    set(key, DownloadState.Running(null))
                    val load = suspend {
                        source.load(server, level.name) { p -> set(key, DownloadState.Running(p)) }
                    }
                    try {
                        save(server, serverTitle, load())
                    } catch (e: MissingFiles) {
                        save(server, serverTitle, load())
                    }
                }
                set(key, null)
            } catch (e: CancellationException) {
                set(key, null)
                throw e
            } catch (e: Exception) {
                val resource = when (e) {
                    is LoadFailure -> e.kind?.wireName
                    is ExtraResourceFailure -> e.resource
                    else -> null
                }
                set(key, DownloadState.Failed(downloadErrorText(e), resource))
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { synchronized(this) { if (jobs[key] === job) jobs.remove(key) } }
        return job
    }

    fun cancel(key: LevelKey) {
        synchronized(this) { jobs[key] }?.cancel()
    }

    /**
     * Cancels every queued and running download and drops them from [states] in one update: a queue of thousands
     * leaving one by one would copy the state map once per level. (A job cancelled before it started never reaches
     * its own cleanup either.)
     */
    fun cancelAll() {
        val cancelled = synchronized(this) {
            val keys = jobs.keys.toSet()
            keys.forEach { titles.remove(it) }
            jobs.values.toList().forEach { it.cancel() }
            keys
        }
        _states.update { states -> states.filterKeys { it !in cancelled } }
    }

    /** The title of [key] while it is in [states]; null otherwise. */
    @Synchronized
    fun titleOf(key: LevelKey): String? = titles[key]

    /** Adds a level that was just loaded for playing ("Save levels I play"); fetches its cover and preview first. */
    suspend fun saveLoaded(server: ServerAddress, serverTitle: String, loaded: LoadedResources) {
        save(server, serverTitle, loaded)
    }

    private suspend fun save(server: ServerAddress, serverTitle: String, loaded: LoadedResources) {
        val item = library.detailsOf(loaded).item
        val cover = fetchExtra(server, item.cover, "level.cover")
        val preview = fetchExtra(server, item.preview, "level.preview")
        library.add(library.entryFrom(server, serverTitle, loaded, cover, preview))
    }

    private suspend fun fetchExtra(server: ServerAddress, srl: Srl?, resource: String): String? = try {
        source.fetchSrl(server, srl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ExtraResourceFailure(resource, e)
    }

    private fun set(key: LevelKey, state: DownloadState?) {
        if (state == null) synchronized(this) { titles.remove(key) }
        _states.update { if (state == null) it - key else it + (key to state) }
    }

    companion object {
        /** Levels downloading at once: hides request latency, gentle on community servers. */
        const val PARALLEL = 3
    }
}

/** What the details pane says when a download fails. */
fun downloadErrorText(e: Throwable): String {
    val inner = (e as? ExtraResourceFailure)?.cause ?: e
    val cause = (inner as? LoadFailure)?.cause ?: inner
    return when {
        e.isDiskFull() -> "Not enough storage. Delete some levels and try again."
        cause is HashMismatch -> "The download was corrupted"
        cause is ServerApiException -> "The server returned an error (${cause.code})"
        cause is IOException -> "Couldn't reach the server"
        else -> cause.message ?: "Something went wrong"
    }
}
