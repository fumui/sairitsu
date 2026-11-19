package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.ItemApi
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
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

/** A skin a server offers, with the background and particles of the same name when it has them. */
data class SkinOffer(val name: String, val title: String, val refs: List<ResourceRef>)

/** A skin saved on this device. [files]: cache key by [ResourceKind.wireName]. */
@Serializable
data class SavedSkin(val id: String, val title: String, val server: String, val files: Map<String, String>)

/**
 * The skins saved on this device, kept apart from the level cache so eviction never removes them. Applying one changes
 * only the files given to the runtime: a level's cache keys stay its own, so the library and replays never see it.
 */
class SkinLibrary(dir: File, private val client: OkHttpClient) {
    private val manifest = File(dir.apply { mkdirs() }, "skins.json")
    private val cache = ResourceCache(File(dir, "files"))
    private val mutex = Mutex()
    private val _skins = MutableStateFlow(readManifest())
    val skins: StateFlow<List<SavedSkin>> = _skins.asStateFlow()

    /** The offer for one skin list item; null when it lacks a name, data or texture. */
    fun offer(server: ServerAddress, item: JsonObject): SkinOffer? {
        val name = text(item, "name") ?: return null
        val skinRefs = refs(item, server, SKIN_KINDS) ?: return null
        return SkinOffer(name, text(item, "title") ?: name, skinRefs)
    }

    /**
     * Downloads [offer] with the server's background and particles of the same name, when it has them, and saves it,
     * replacing the same skin added earlier. On failure nothing changes.
     */
    suspend fun add(server: ServerAddress, offer: SkinOffer): SavedSkin = withContext(Dispatchers.IO) {
        mutex.withLock {
            val files = LinkedHashMap<String, String>()
            try {
                val companions = companion(server, "backgrounds", offer.name, BACKGROUND_KINDS).orEmpty() +
                    companion(server, "particles", offer.name, PARTICLE_KINDS).orEmpty()
                for (ref in offer.refs + companions) {
                    currentCoroutineContext().ensureActive()
                    val hash = ref.hash?.takeIf { SHA1.matches(it) }?.lowercase()
                    val key = hash ?: ("url-" + sha1Hex(ref.url))
                    files[ref.kind.wireName] = key
                    if (hash == null || !cache.has(key)) download(ref.url, hash, key)
                }
            } catch (e: Throwable) {
                cache.delete(files.values.toSet() - keysOf(_skins.value))
                throw e
            }
            val saved = SavedSkin(sha1Hex("${server.linkAddress}|${offer.name}"), offer.title, server.linkAddress, files)
            val current = _skins.value
            commit(if (current.any { it.id == saved.id }) current.map { if (it.id == saved.id) saved else it } else current + saved)
            saved
        }
    }

    /** Deletes the skin and the files no other saved skin uses. */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { commit(_skins.value.filterNot { it.id == id }) }
    }

    /** [loaded] with the look of skin [id]; unchanged when there is no such skin or one of its files is missing. */
    fun applyTo(loaded: LoadedResources, id: String?): LoadedResources {
        val skin = _skins.value.firstOrNull { it.id == id } ?: return loaded
        val replaced = ResourceKind.entries.mapNotNull { kind ->
            val key = skin.files[kind.wireName] ?: return@mapNotNull null
            kind to (cache.file(key) ?: return loaded)
        }
        return loaded.copy(files = loaded.files + replaced)
    }

    private fun keysOf(skins: List<SavedSkin>): Set<String> = skins.flatMapTo(HashSet()) { it.files.values }

    private fun commit(next: List<SavedSkin>) {
        val tmp = File(manifest.parentFile, "${manifest.name}.tmp")
        tmp.writeText(ServerJson.encodeToString(MANIFEST, next), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val released = keysOf(_skins.value) - keysOf(next)
        _skins.value = next
        cache.delete(released)
    }

    private fun readManifest(): List<SavedSkin> = try {
        if (manifest.isFile) ServerJson.decodeFromString(MANIFEST, manifest.readText(Charsets.UTF_8)) else emptyList()
    } catch (e: SerializationException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    } catch (e: IOException) {
        emptyList()
    }

    /**
     * The [kinds] of the item named [name] in [section] (`backgrounds` or `particles`) on [server]; null when the server
     * has none by that name.
     */
    private suspend fun companion(
        server: ServerAddress,
        section: String,
        name: String,
        kinds: List<ResourceKind>,
    ): List<ResourceRef>? {
        val item = try {
            ItemApi(server, client).details(section, name)["item"] as? JsonObject
        } catch (e: ServerApiException) {
            if (e.code == 404) return null
            throw e
        }
        return item?.let { refs(it, server, kinds) }
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

    private companion object {
        val MANIFEST = ListSerializer(SavedSkin.serializer())
        val SKIN_KINDS = listOf(ResourceKind.SKIN_DATA, ResourceKind.SKIN_TEXTURE)
        val BACKGROUND_KINDS = listOf(
            ResourceKind.BACKGROUND_IMAGE,
            ResourceKind.BACKGROUND_DATA,
            ResourceKind.BACKGROUND_CONFIGURATION,
        )
        val PARTICLE_KINDS = listOf(ResourceKind.PARTICLE_DATA, ResourceKind.PARTICLE_TEXTURE)
        val SHA1 = Regex("[0-9a-fA-F]{40}")
    }
}
