package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.ServerApiException
import my.fumui.sairitsu.data.model.ServerJson
import my.fumui.sairitsu.gameplay.model.ResourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** An item a server offers: its [kind], and the files of [ItemKind.resources]. */
data class ItemOffer(val kind: ItemKind, val name: String, val title: String, val refs: List<ResourceRef>)

/**
 * The items saved on this device, kept apart from the level cache so eviction never removes them, plus temporary
 * entries (previews) that live in memory until [promote]d. Applying items changes only the files given to the runtime:
 * a level's cache keys stay its own, so the library and replays never see it.
 */
class ItemLibrary(dir: File, private val client: OkHttpClient) {
    init {
        // Before items.json is read. A failure keeps skins.json, so the next start retries and its files stay protected.
        dir.mkdirs()
        try {
            ItemMigration.migrateFiles(dir)
        } catch (e: Exception) {
            // Unreadable or unwritable: skins.json stays.
        }
    }

    private val manifest = File(dir, "items.json")
    private val cache = ResourceCache(File(dir, "files"))
    private val mutex = Mutex()
    private val temporary = ConcurrentHashMap<String, SavedItem>()
    private val read = readManifest()
    private val _items = MutableStateFlow(read.orEmpty())
    val items: StateFlow<List<SavedItem>> = _items.asStateFlow()

    init {
        // Files no saved item uses are previews of an earlier run. A legacy skins.json (not migrated yet) still owns its files.
        // An items.json that exists but cannot be read leaves unknown owners, so nothing is swept.
        if (read != null) legacyKeys(File(dir, "skins.json"))?.let { legacy -> cache.delete(cache.keys() - keysOf(_items.value) - legacy) }
    }

    /** The cache keys listed in the legacy manifest [file]; empty when there is none, null when it cannot be read. */
    private fun legacyKeys(file: File): Set<String>? {
        if (!file.isFile) return emptySet()
        return try {
            val entries = ServerJson.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonArray ?: return null
            entries.flatMapTo(HashSet()) { entry ->
                ((entry as? JsonObject)?.get("files") as? JsonObject)?.values.orEmpty().mapNotNull { text(it) }
            }
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun text(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The offer for one list item of [kind]; null when it lacks a name or one of the kind's resources. */
    fun offer(kind: ItemKind, server: ServerAddress, item: JsonObject): ItemOffer? {
        val name = text(item, "name") ?: return null
        val refs = refs(item, server, kind.resources) ?: return null
        return ItemOffer(kind, name, text(item, "title") ?: name, refs)
    }

    /** Downloads [offer] and saves it, replacing the same item added earlier. On failure nothing changes. */
    suspend fun add(server: ServerAddress, offer: ItemOffer): SavedItem = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = download(server, offer)
            temporary.remove(saved.id)
            commit(replacing(saved))
            saved
        }
    }

    /** Downloads [offer] for a trial run without saving it; it stays until [promote] or the next start. */
    suspend fun preview(server: ServerAddress, offer: ItemOffer): SavedItem = withContext(Dispatchers.IO) {
        mutex.withLock {
            download(server, offer).also { temporary[it.id] = it }
        }
    }

    /** Saves the temporary entry [id] without downloading again; null when there is none. */
    suspend fun promote(id: String): SavedItem? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val item = temporary.remove(id) ?: return@withLock null
            commit(replacing(item))
            item
        }
    }

    /** Deletes the item and the files no other item uses. */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            temporary.remove(id)
            commit(_items.value.filterNot { it.id == id })
        }
    }

    /** The saved or temporary item [id]. */
    fun find(id: String): SavedItem? = _items.value.firstOrNull { it.id == id } ?: temporary[id]

    /** [item]'s cached file of [resource]; null when the item has none or it is gone from the cache. */
    fun file(item: SavedItem, resource: ResourceKind): File? = item.files[resource.wireName]?.let(cache::file)

    /**
     * [loaded] with the items chosen in [choices] (item id by kind; null keeps the level's own). A kind is replaced only
     * when its item exists, has the kind and every one of its files is in the cache.
     */
    fun applyTo(loaded: LoadedResources, choices: Map<ItemKind, String?>): LoadedResources {
        val replaced = HashMap<ResourceKind, File>()
        for ((kind, id) in choices) {
            val item = id?.let(::find)?.takeIf { it.kind == kind } ?: continue
            val files = kind.resources.map { resource ->
                val key = item.files[resource.wireName] ?: return@map null
                cache.file(key)?.let { resource to it }
            }
            if (files.all { it != null }) replaced += files.filterNotNull()
        }
        return if (replaced.isEmpty()) loaded else loaded.copy(files = loaded.files + replaced)
    }

    /** Downloads the files of [offer]; on failure the files nothing else uses are deleted. */
    private suspend fun download(server: ServerAddress, offer: ItemOffer): SavedItem {
        val files = LinkedHashMap<String, String>()
        try {
            for (ref in offer.refs) {
                currentCoroutineContext().ensureActive()
                val hash = ref.hash?.takeIf { SHA1.matches(it) }?.lowercase()
                val key = hash ?: ("url-" + sha1Hex(ref.url))
                files[ref.kind.wireName] = key
                if (hash == null || !cache.has(key)) download(ref.url, hash, key)
            }
        } catch (e: Throwable) {
            cache.delete(files.values.toSet() - keysOf(_items.value) - keysOf(temporary.values))
            throw e
        }
        return SavedItem(idOf(offer.kind, server.linkAddress, offer.name), offer.kind, offer.title, server.linkAddress, files)
    }

    private fun replacing(item: SavedItem): List<SavedItem> {
        val current = _items.value
        return if (current.any { it.id == item.id }) current.map { if (it.id == item.id) item else it } else current + item
    }

    private fun keysOf(items: Collection<SavedItem>): Set<String> = items.flatMapTo(HashSet()) { it.files.values }

    private fun commit(next: List<SavedItem>) {
        val tmp = File(manifest.parentFile, "${manifest.name}.tmp")
        tmp.writeText(ServerJson.encodeToString(MANIFEST, next), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val released = keysOf(_items.value) - keysOf(next) - keysOf(temporary.values)
        _items.value = next
        cache.delete(released)
    }

    /** The saved items; empty when there is no items.json, null when it exists but cannot be read. */
    private fun readManifest(): List<SavedItem>? = try {
        if (manifest.isFile) ServerJson.decodeFromString(MANIFEST, manifest.readText(Charsets.UTF_8)) else emptyList()
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IOException) {
        null
    }

    /** The resources of [item] for [kinds]; null when one is missing. */
    private fun refs(item: JsonObject, server: ServerAddress, kinds: List<ResourceKind>): List<ResourceRef>? =
        kinds.map { kind ->
            val srl = item[kind.wireName.substringAfter('.')] as? JsonObject ?: return null
            val url = server.resolve(text(srl, "url")) ?: return null
            ResourceRef(kind, url, text(srl, "hash"))
        }

    private fun text(item: JsonObject, key: String): String? = (item[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun download(url: String, hash: String?, key: String) {
        withCall(client.newCall(Request.Builder().url(url).build())) { response ->
            if (!response.isSuccessful) throw ServerApiException(response.code, "HTTP ${response.code} for $url")
            cache.put(key, hash) { out -> response.body!!.byteStream().use { it.copyTo(out) } }
        }
    }

    companion object {
        private val MANIFEST = ListSerializer(SavedItem.serializer())
        private val SHA1 = Regex("[0-9a-fA-F]{40}")

        /** The id of the [kind] item [name] on the server at [linkAddress]; skins keep the ids of earlier versions. */
        fun idOf(kind: ItemKind, linkAddress: String, name: String): String =
            sha1Hex(if (kind == ItemKind.Skin) "$linkAddress|$name" else "${kind.wireType}|$linkAddress|$name")
    }
}
