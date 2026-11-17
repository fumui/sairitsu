package my.fumui.sairitsu.gameplay

import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.Srl
import my.fumui.sairitsu.library.DownloadSource
import my.fumui.sairitsu.data.ServerApiException
import my.fumui.sairitsu.data.model.ServerJson
import my.fumui.sairitsu.gameplay.model.MissingResource
import my.fumui.sairitsu.gameplay.model.ResourceRef
import my.fumui.sairitsu.gameplay.model.resolveResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException

data class LoadProgress(
    val done: Int,
    val total: Int,
    val kind: ResourceKind?,
    val bytes: Long,
    val totalBytes: Long?,
)

/** A level's files, with the [ResourceCache] keys they are stored under and the key of the cached details JSON. */
data class LoadedResources(
    val files: Map<ResourceKind, File>,
    val keys: Map<ResourceKind, String> = emptyMap(),
    val detailsKey: String = "",
)

/** Whether this (or a cause) is the device running out of storage. */
fun Throwable.isDiskFull(): Boolean = generateSequence(this) { it.cause }.take(8).any { e ->
    val m = e.message.orEmpty()
    "ENOSPC" in m || "No space left" in m
}

/** A downloaded level's cache keys (see the library index). */
data class LocalLevel(val detailsKey: String, val resourceKeys: Map<ResourceKind, String>)

/**
 * Loading failed; [kind] and [url] name the resource when one caused it. [damaged]: the level is downloaded but some
 * of its files are missing and could not be fetched again.
 */
class LoadFailure(
    val kind: ResourceKind?,
    val url: String?,
    cause: Throwable,
    val damaged: Boolean = false,
) : Exception(cause.message, cause)

/**
 * Fetches a level's fifteen resources into [cache]. A level [localLevel] knows (a downloaded one) is served from its
 * cached files without any network request; [pinnedKeys] (every downloaded level's keys) are never evicted.
 */
class LevelLoader(
    private val client: OkHttpClient,
    private val cache: ResourceCache,
    private val pinnedKeys: () -> Set<String> = { emptySet() },
    private val localLevel: (LevelKey) -> LocalLevel? = { null },
) : DownloadSource {
    override suspend fun load(
        server: ServerAddress,
        level: String,
        onProgress: (LoadProgress) -> Unit,
    ): LoadedResources = withContext(Dispatchers.IO) {
        val local = localLevel(LevelKey(server.linkAddress, level))
            ?: return@withContext loadOnline(server, level, onProgress)
        val files = localFiles(local)
        if (files != null) {
            onProgressSafe(onProgress, LoadProgress(files.size, files.size, null, 0, null))
            return@withContext LoadedResources(files, local.resourceKeys, local.detailsKey)
        }
        try {
            loadOnline(server, level, onProgress)
        } catch (e: LoadFailure) {
            throw LoadFailure(e.kind, e.url, e.cause ?: e, damaged = true)
        }
    }

    /**
     * The downloaded level's files when every one of them (and its details) is cached; null otherwise. An optional kind
     * the level has no key for (it had none on the server) is left out.
     */
    private fun localFiles(local: LocalLevel): Map<ResourceKind, File>? {
        if (!cache.has(local.detailsKey)) return null
        val files = LinkedHashMap<ResourceKind, File>()
        for (kind in ResourceKind.entries) {
            val key = local.resourceKeys[kind] ?: if (kind.optional) continue else return null
            files[kind] = cache.file(key) ?: return null
        }
        return files
    }

    /**
     * Caches the resource [srl] (a cover or preview) under the same key rules as level resources and returns its key;
     * null when it has no URL. These files are only cosmetic, so when the body never matches the advertised hash (a
     * server that replaced the file but not its hash) it is kept unverified under its URL key instead of failing.
     */
    override suspend fun fetchSrl(server: ServerAddress, srl: Srl?): String? = withContext(Dispatchers.IO) {
        val url = server.resolve(srl?.url) ?: return@withContext null
        val hash = srl?.hash?.takeIf { SHA1.matches(it) }?.lowercase()
        val urlKey = "url-" + sha1Hex(url)
        val key = hash ?: urlKey
        if (cache.file(key) != null) return@withContext key
        if (hash != null && cache.file(urlKey) != null) return@withContext urlKey
        try {
            try {
                downloadWithRetry(url, hash, key, null) { _, _ -> }
                key
            } catch (e: LoadFailure) {
                if (hash == null || e.cause !is HashMismatch) throw e
                downloadWithRetry(url, null, urlKey, null) { _, _ -> }
                urlKey
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoadFailure) {
            throw e
        } catch (e: Exception) {
            throw LoadFailure(null, url, e)
        }
    }

    /**
     * Only the level's engine configuration (all the "Level options" screen needs): a downloaded level's copy without
     * any request, else the cached file, else the details and that one file from the server.
     */
    suspend fun engineConfiguration(server: ServerAddress, level: String): File = withContext(Dispatchers.IO) {
        val kind = ResourceKind.ENGINE_CONFIGURATION
        localLevel(LevelKey(server.linkAddress, level))?.resourceKeys?.get(kind)
            ?.let { cache.file(it) }?.let { return@withContext it }
        val ref = details(server, level).refs.firstOrNull { it.kind == kind }
            ?: throw LoadFailure(kind, null, IOException("The level has no engine configuration"))
        val key = keyOf(ref)
        try {
            cache.file(key) ?: downloadWithRetry(ref.url, verifiedHash(ref), key, kind) { _, _ -> }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoadFailure) {
            throw e
        } catch (e: Exception) {
            throw LoadFailure(kind, ref.url, e)
        }
    }

    private class Details(val key: String, val refs: List<ResourceRef>)

    /** The level's details (network, else the cached copy), failures as [LoadFailure]. */
    private suspend fun details(server: ServerAddress, level: String): Details {
        val detailsKey = "details-" + sha1Hex("${server.baseUrl}|$level")
        return try {
            Details(detailsKey, fetchDetails(server, level, detailsKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MissingResource) {
            throw LoadFailure(e.kind, null, e)
        } catch (e: Exception) {
            throw LoadFailure(null, null, e)
        }
    }

    private suspend fun loadOnline(
        server: ServerAddress,
        level: String,
        onProgress: (LoadProgress) -> Unit,
    ): LoadedResources {
        val details = details(server, level)
        val detailsKey = details.key
        val refs = details.refs

        val keys = refs.associateWith { keyOf(it) }
        val pinned = keys.values.toSet() + detailsKey
        val files = LinkedHashMap<ResourceKind, File>()
        onProgressSafe(onProgress, LoadProgress(0, refs.size, null, 0, null))
        for ((index, ref) in refs.withIndex()) {
            currentCoroutineContext().ensureActive()
            val key = keys.getValue(ref)
            files[ref.kind] = try {
                cache.file(key) ?: downloadWithRetry(ref.url, verifiedHash(ref), key, ref.kind) { bytes, total ->
                    onProgress(LoadProgress(index, refs.size, ref.kind, bytes, total))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoadFailure) {
                throw e
            } catch (e: Exception) {
                throw LoadFailure(ref.kind, ref.url, e)
            }
            onProgressSafe(onProgress, LoadProgress(index + 1, refs.size, ref.kind, 0, null))
        }
        cache.evict(pinned + pinnedKeys())
        return LoadedResources(files, refs.associate { it.kind to keys.getValue(it) }, detailsKey)
    }

    private fun onProgressSafe(onProgress: (LoadProgress) -> Unit, p: LoadProgress) {
        try {
            onProgress(p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LoadFailure(null, null, e)
        }
    }

    /** A null or malformed hash cannot be verified; the resource is then keyed by URL. */
    private fun verifiedHash(ref: ResourceRef): String? = ref.hash?.takeIf { SHA1.matches(it) }?.lowercase()

    private fun keyOf(ref: ResourceRef): String = verifiedHash(ref) ?: ("url-" + sha1Hex(ref.url))

    private fun parseRefs(text: String, server: ServerAddress): List<ResourceRef> {
        val item = ServerJson.parseToJsonElement(text).jsonObject["item"] as? JsonObject
            ?: throw IOException("Level details have no item")
        return resolveResources(item, server)
    }

    /**
     * Fetches the details and caches the body only when it resolves to every required resource. Any
     * failure of the network copy (transport, HTTP status, unusable body) falls back to the cached copy.
     */
    private suspend fun fetchDetails(server: ServerAddress, level: String, key: String): List<ResourceRef> {
        val url = server.baseUrl.newBuilder()
            .addPathSegment("sonolus").addPathSegment("levels").addPathSegment(level).build()
        try {
            val bytes = withCall(client.newCall(Request.Builder().url(url).build())) { response ->
                if (!response.isSuccessful) throw ServerApiException(response.code, "HTTP ${response.code} for $url")
                response.body!!.bytes()
            }
            val refs = parseRefs(String(bytes, Charsets.UTF_8), server)
            cache.put(key, null) { it.write(bytes) }
            return refs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val cached = cache.file(key) ?: throw e
            return try {
                parseRefs(cached.readText(Charsets.UTF_8), server)
            } catch (_: Exception) {
                throw e
            }
        }
    }

    /** Runs [block] on the response of [call]; cancelling the coroutine cancels the call. */
    private suspend fun <T> withCall(call: Call, block: (Response) -> T): T = coroutineScope {
        val watcher = launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use(block)
        } finally {
            watcher.cancel()
        }
    }

    /** Downloads [url] into [key], verified against [hash] when not null; one retry. [onBytes]: (bytes so far, total). */
    private suspend fun downloadWithRetry(
        url: String,
        hash: String?,
        key: String,
        kind: ResourceKind?,
        onBytes: (Long, Long?) -> Unit,
    ): File {
        var last: IOException? = null
        repeat(2) {
            try {
                return download(url, hash, key, onBytes)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                last = e
            }
        }
        throw LoadFailure(kind, url, last!!)
    }

    private suspend fun download(url: String, hash: String?, key: String, onBytes: (Long, Long?) -> Unit): File {
        val ctx = currentCoroutineContext()
        val request = Request.Builder().url(url).build()
        return withCall(client.newCall(request)) { response ->
            if (!response.isSuccessful) throw ServerApiException(response.code, "HTTP ${response.code} for $url")
            val body = response.body!!
            val totalBytes = body.contentLength().takeIf { it >= 0 }
            cache.put(key, hash) { out ->
                var bytes = 0L
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        bytes += n
                        onBytes(bytes, totalBytes)
                    }
                }
            }
        }
    }

    private companion object {
        val SHA1 = Regex("[0-9a-fA-F]{40}")
    }
}
