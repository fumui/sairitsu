package my.fumui.sairitsu.data

import my.fumui.sairitsu.data.model.ItemList
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.ServerJson
import my.fumui.sairitsu.data.model.savesLevelList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/** What a catalog's sync is doing. */
sealed interface SyncStatus {
    data object Idle : SyncStatus

    /** Downloading the whole list: [done] of [total] levels (total unknown for cursor servers). */
    data class Downloading(val done: Int, val total: Int?) : SyncStatus

    /** Looking for levels added since the last sync. */
    data object Checking : SyncStatus

    data class Failed(val message: String) : SyncStatus
}

/**
 * One server's saved level list, newest first (the server's default order). [complete] once the whole
 * list has been downloaded; until then [items] grows as pages arrive. [disabled] when the server opted out
 * of having its list saved (nothing is downloaded or kept for it).
 */
data class CatalogState(
    val items: List<LevelItem> = emptyList(),
    val loaded: Boolean = false,
    val complete: Boolean = false,
    val status: SyncStatus = SyncStatus.Idle,
    val syncedAt: Long = 0,
    val disabled: Boolean = false,
)

/** The bits of a catalog the Online Servers cards show, without loading its levels. */
data class CatalogSummary(val count: Int, val complete: Boolean, val syncedAt: Long, val status: SyncStatus)

/** What the Online Servers screen does with the saved catalogs. */
interface ServerCatalogs {
    /** Per server address; filled by [loadSummaries] and by syncs. */
    val summaries: StateFlow<Map<String, CatalogSummary>>

    fun loadSummaries(servers: List<String>)

    /** Starts (or resumes) downloading [server]'s list in the background. */
    fun open(server: ServerAddress)

    fun refresh(server: ServerAddress)

    fun delete(server: String)
}

@Serializable
internal data class CatalogMeta(
    val complete: Boolean = false,
    /** The first page the initial download hasn't stored yet (it resumes from here). */
    val nextPage: Int = 0,
    val pageCount: Int = -1,
    val count: Int = 0,
    val syncedAt: Long = 0,
)

/** A catalog on disk: `meta.json` and `items.jsonl` (one level per line) in a folder per server. */
internal class CatalogFiles(private val root: File) {
    private fun dir(server: String) = File(root, server.replace(Regex("[^A-Za-z0-9.-]"), "_"))

    fun readMeta(server: String): CatalogMeta? = runCatching {
        ServerJson.decodeFromString(CatalogMeta.serializer(), File(dir(server), META).readText())
    }.getOrNull()

    fun readItems(server: String): List<LevelItem> {
        val file = File(dir(server), ITEMS)
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                if (line.isBlank()) null
                else runCatching { ServerJson.decodeFromString(LevelItem.serializer(), line) }.getOrNull()
            }.toList()
        }
    }

    fun append(server: String, items: List<LevelItem>, meta: CatalogMeta) {
        val dir = dir(server).apply { mkdirs() }
        File(dir, ITEMS).appendText(encode(items))
        writeAtomically(File(dir, META), ServerJson.encodeToString(CatalogMeta.serializer(), meta))
    }

    fun replace(server: String, items: List<LevelItem>, meta: CatalogMeta) {
        val dir = dir(server).apply { mkdirs() }
        writeAtomically(File(dir, ITEMS), encode(items))
        writeAtomically(File(dir, META), ServerJson.encodeToString(CatalogMeta.serializer(), meta))
    }

    fun writeMeta(server: String, meta: CatalogMeta) {
        val dir = dir(server).apply { mkdirs() }
        writeAtomically(File(dir, META), ServerJson.encodeToString(CatalogMeta.serializer(), meta))
    }

    fun delete(server: String) {
        dir(server).deleteRecursively()
    }

    private fun encode(items: List<LevelItem>) = buildString {
        items.forEach { append(ServerJson.encodeToString(LevelItem.serializer(), it)).append('\n') }
    }

    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Couldn't save ${file.name}")
        }
    }

    private companion object {
        const val META = "meta.json"
        const val ITEMS = "items.jsonl"
    }
}

/**
 * Every server's level list saved on the device, kept up to date in the background:
 *  - the first [open] downloads the whole list (resuming where an interrupted download stopped);
 *  - later opens fetch pages from the top until one has a level already saved, plus the server's
 *    `#NEWEST` section (new difficulties of old songs sit with their song, not at the top);
 *  - [refresh] downloads the whole list again, replacing the saved one when done.
 * Edited or removed levels are only picked up by [refresh].
 */
class LevelCatalog internal constructor(
    private val files: CatalogFiles,
    private val sourceFor: (ServerAddress) -> LevelSource,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ServerCatalogs {
    constructor(root: File, sourceFor: (ServerAddress) -> LevelSource, scope: CoroutineScope) :
        this(CatalogFiles(root), sourceFor, scope)

    private val states = HashMap<String, MutableStateFlow<CatalogState>>()
    private val jobs = HashMap<String, Job>()
    private val locks = HashMap<String, Mutex>()
    private val _summaries = MutableStateFlow<Map<String, CatalogSummary>>(emptyMap())

    /** Per server address, what its saved catalog holds; only for servers read this session. */
    override val summaries: StateFlow<Map<String, CatalogSummary>> = _summaries.asStateFlow()

    @Synchronized
    private fun flow(server: String) = states.getOrPut(server) { MutableStateFlow(CatalogState()) }

    @Synchronized
    private fun lock(server: String) = locks.getOrPut(server) { Mutex() }

    /** [server]'s catalog; call [open] to load and sync it. */
    fun state(server: ServerAddress): StateFlow<CatalogState> = flow(server.linkAddress).asStateFlow()

    /** Reads the saved summary of each of [servers] for the server cards. */
    override fun loadSummaries(servers: List<String>) {
        scope.launch(io) {
            servers.forEach { server ->
                if (server in _summaries.value) return@forEach
                val meta = files.readMeta(server) ?: return@forEach
                publishSummary(server, meta.count, meta.complete, meta.syncedAt, SyncStatus.Idle)
            }
        }
    }

    /** Loads [server]'s saved levels (once) and starts a sync unless one is running. */
    override fun open(server: ServerAddress) = start(server, full = false)

    /** Downloads [server]'s whole list again in the background. */
    override fun refresh(server: ServerAddress) = start(server, full = true)

    /** Forgets [server]'s saved levels (the server was removed or moved). */
    override fun delete(server: String) {
        synchronized(this) { jobs.remove(server) }?.cancel()
        scope.launch(io) {
            lock(server).withLock { files.delete(server) }
            flow(server).value = CatalogState()
            _summaries.update { it - server }
        }
    }

    private fun start(server: ServerAddress, full: Boolean) {
        val key = server.linkAddress
        synchronized(this) {
            val running = jobs[key]
            if (running?.isActive == true) {
                if (!full) return
                running.cancel()
            }
            jobs[key] = scope.launch(io) { lock(key).withLock { sync(server, full) } }
        }
    }

    private suspend fun sync(server: ServerAddress, full: Boolean) {
        val key = server.linkAddress
        val state = flow(key)
        if (!state.value.loaded) {
            val meta = files.readMeta(key) ?: CatalogMeta()
            val items = if (meta.count > 0 || meta.nextPage > 0) files.readItems(key) else emptyList()
            state.value = CatalogState(items, loaded = true, complete = meta.complete, syncedAt = meta.syncedAt)
        }
        val source = sourceFor(server)
        // A server that opts out never has a list saved. If its info can't be read we can't tell whether it opts
        // out, so fail without any list request (a crawl of a huge catalog); the saved copy stays usable.
        val optedOut = try {
            !source.serverInfo().savesLevelList
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(status = SyncStatus.Failed(syncError(e))) }
            publishSummary(key)
            return
        }
        if (optedOut) {
            files.delete(key)
            state.value = CatalogState(loaded = true, disabled = true)
            _summaries.update { it - key }
            return
        }
        try {
            when {
                full -> redownload(key, source)
                !state.value.complete -> download(key, source)
                else -> checkForNew(key, source)
            }
            state.update { it.copy(status = SyncStatus.Idle) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(status = SyncStatus.Failed(syncError(e))) }
        }
        publishSummary(key)
    }

    /** The initial download: pages from where the last attempt stopped, appended and saved as they arrive. */
    private suspend fun download(key: String, source: LevelSource) {
        val state = flow(key)
        val meta = files.readMeta(key) ?: CatalogMeta()
        val seen = state.value.items.mapTo(HashSet()) { it.name }
        var items = state.value.items

        val first = source.levelList(emptyMap(), null)
        if (first.cursor != null) {
            downloadByCursor(key, source, first)
            return
        }
        val pageCount = maxOf(first.pageCount, 1)
        var next = meta.nextPage
        if (next == 0) {
            val fresh = first.items.filter { seen.add(it.name) }
            items = items + fresh
            next = 1
            save(key, fresh, items, next, pageCount, complete = pageCount <= 1)
        }
        while (next < pageCount) {
            val pages = (next until minOf(pageCount, next + PARALLEL)).toList()
            val results = fetchPages(source, pages)
            val fresh = results.flatMap { it.items }.filter { seen.add(it.name) }
            items = items + fresh
            next = pages.last() + 1
            save(key, fresh, items, next, pageCount, complete = next >= pageCount)
        }
        // The server may now have fewer pages than when an interrupted download began.
        if (!state.value.complete) save(key, emptyList(), items, next, pageCount, complete = true)
    }

    private suspend fun downloadByCursor(key: String, source: LevelSource, first: ItemList) {
        // Cursors can't be resumed reliably: start over and replace whatever was saved.
        val seen = HashSet<String>()
        val items = ArrayList<LevelItem>()
        var page: ItemList? = first
        while (page != null) {
            items += page.items.filter { seen.add(it.name) }
            flow(key).update { it.copy(items = items.toList(), status = SyncStatus.Downloading(items.size, null)) }
            page = page.cursor?.let { source.levelList(emptyMap(), it) }
        }
        val meta = CatalogMeta(complete = true, count = items.size, syncedAt = now())
        files.replace(key, items, meta)
        flow(key).update { it.copy(items = items, complete = true, syncedAt = meta.syncedAt) }
    }

    private fun save(key: String, fresh: List<LevelItem>, items: List<LevelItem>, next: Int, pageCount: Int, complete: Boolean) {
        val syncedAt = if (complete) now() else 0
        files.append(key, fresh, CatalogMeta(complete, next, pageCount, items.size, syncedAt))
        val perPage = if (next > 0) items.size.toDouble() / next else 0.0
        val total = (perPage * pageCount).toInt().coerceAtLeast(items.size)
        flow(key).update {
            it.copy(
                items = items,
                complete = complete,
                syncedAt = if (complete) syncedAt else it.syncedAt,
                status = if (complete) SyncStatus.Idle else SyncStatus.Downloading(items.size, total),
            )
        }
        publishSummary(key)
    }

    /** Levels added since the last sync: top pages until one holds a saved level, plus the `#NEWEST` section. */
    private suspend fun checkForNew(key: String, source: LevelSource) {
        val state = flow(key)
        state.update { it.copy(status = SyncStatus.Checking) }
        val known = state.value.items.mapTo(HashSet()) { it.name }
        val added = LinkedHashMap<String, LevelItem>()

        var page = 0
        var cursor: String? = null
        while (page < MAX_CHECK_PAGES) {
            val result = source.levelList(if (page > 0 && cursor == null) mapOf("page" to "$page") else emptyMap(), cursor)
            var hitKnown = false
            result.items.forEach { if (it.name in known) hitKnown = true else added.putIfAbsent(it.name, it) }
            page++
            cursor = result.cursor
            val more = cursor != null || (result.pageCount > page)
            if (hitKnown || !more || result.items.isEmpty()) break
        }
        runCatching { source.levelInfo() }.getOrNull()?.sections
            ?.filter { it.title == NEWEST }
            ?.flatMap { it.levels(ServerJson) }
            ?.forEach { if (it.name !in known) added.putIfAbsent(it.name, it) }

        val syncedAt = now()
        val items = added.values.toList() + state.value.items
        val meta = CatalogMeta(complete = true, count = items.size, syncedAt = syncedAt)
        // Only rewrite the (large) level file when something was added.
        if (added.isNotEmpty()) files.replace(key, items, meta) else files.writeMeta(key, meta)
        state.update { it.copy(items = items, syncedAt = syncedAt) }
    }

    /** Downloads the whole list into a new catalog; the saved one stays in use until it finishes. */
    private suspend fun redownload(key: String, source: LevelSource) {
        val state = flow(key)
        state.update { it.copy(status = SyncStatus.Downloading(0, null)) }
        val first = source.levelList(emptyMap(), null)
        val seen = HashSet<String>()
        val items = ArrayList<LevelItem>()
        if (first.cursor != null) {
            var page: ItemList? = first
            while (page != null) {
                items += page.items.filter { seen.add(it.name) }
                state.update { it.copy(status = SyncStatus.Downloading(items.size, null)) }
                page = page.cursor?.let { source.levelList(emptyMap(), it) }
            }
        } else {
            val pageCount = maxOf(first.pageCount, 1)
            items += first.items.filter { seen.add(it.name) }
            var next = 1
            while (next < pageCount) {
                val pages = (next until minOf(pageCount, next + PARALLEL)).toList()
                fetchPages(source, pages).forEach { r -> items += r.items.filter { seen.add(it.name) } }
                next = pages.last() + 1
                val total = (items.size.toDouble() / next * pageCount).toInt()
                state.update { it.copy(status = SyncStatus.Downloading(items.size, total)) }
                publishSummary(key)
            }
        }
        val meta = CatalogMeta(complete = true, count = items.size, syncedAt = now())
        files.replace(key, items, meta)
        state.update { it.copy(items = items.toList(), complete = true, syncedAt = meta.syncedAt) }
    }

    private suspend fun fetchPages(source: LevelSource, pages: List<Int>): List<ItemList> = coroutineScope {
        pages.map { page -> async { source.levelList(mapOf("page" to "$page"), null) } }.awaitAll()
    }

    private fun publishSummary(server: String) {
        flow(server).value.let { publishSummary(server, it.items.size, it.complete, it.syncedAt, it.status) }
    }

    private fun publishSummary(server: String, count: Int, complete: Boolean, syncedAt: Long, status: SyncStatus) {
        _summaries.update { it + (server to CatalogSummary(count, complete, syncedAt, status)) }
    }

    private fun syncError(e: Exception): String = when (e) {
        is ServerApiException -> "The server returned an error (${e.code})"
        is IOException -> "Couldn't reach the server"
        else -> e.message ?: "Something went wrong"
    }

    companion object {
        /** Pages fetched at once while downloading a whole list. */
        const val PARALLEL = 6

        /** How far [checkForNew] looks before giving up (a server that changed a lot needs a refresh). */
        const val MAX_CHECK_PAGES = 20

        private const val NEWEST = "#NEWEST"
    }
}
