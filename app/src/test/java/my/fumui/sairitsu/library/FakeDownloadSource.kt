package my.fumui.sairitsu.library

import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.Srl
import my.fumui.sairitsu.gameplay.LoadProgress
import my.fumui.sairitsu.gameplay.LoadedResources
import my.fumui.sairitsu.gameplay.ResourceCache
import my.fumui.sairitsu.gameplay.ResourceKind
import my.fumui.sairitsu.gameplay.sha1Hex
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes a level's files into a real [ResourceCache]. The four engine files are shared by every level
 * (`engine-<ordinal>`); the rest are per level (`<level>-<ordinal>`).
 */
class FakeDownloadSource(private val cache: ResourceCache) : DownloadSource {
    val loads = AtomicInteger()

    /** Runs at the start of every load (may suspend, or throw to fail it). */
    var beforeLoad: suspend (String) -> Unit = {}

    /** Runs after the files are written, before the result is returned. */
    var afterLoad: (LoadedResources) -> Unit = {}

    fun keysOf(level: String): Map<ResourceKind, String> = ResourceKind.entries.associateWith {
        if (it.name.startsWith("ENGINE")) "engine-${it.ordinal}" else "$level-${it.ordinal}"
    }

    fun detailsJson(level: String) = """
        {"item":{"name":"$level","title":"Title $level","rating":24,"artists":"Artist","author":"Author",
          "tags":[{"title":"Expert"},{"title":"#VIRTUAL"}],"engine":{"name":"rush","title":"Next RUSH"},
          "cover":{"url":"/c/$level"},"preview":{"url":"/p/$level"}},
         "description":"About $level"}
    """.trimIndent()

    override suspend fun load(server: ServerAddress, level: String, onProgress: (LoadProgress) -> Unit): LoadedResources {
        loads.incrementAndGet()
        beforeLoad(level)
        onProgress(LoadProgress(0, 15, null, 0, null))
        val keys = keysOf(level)
        for (k in keys.values) if (!cache.has(k)) cache.put(k, null) { it.write(k.toByteArray()) }
        val detailsKey = "details-$level"
        cache.put(detailsKey, null) { it.write(detailsJson(level).toByteArray()) }
        val loaded = LoadedResources(keys.mapValues { cache.file(it.value)!! }, keys, detailsKey)
        afterLoad(loaded)
        return loaded
    }

    /** Thrown by [fetchSrl] for a URL that starts with this prefix. */
    var failSrl: Pair<String, Exception>? = null

    override suspend fun fetchSrl(server: ServerAddress, srl: Srl?): String? {
        val url = srl?.url ?: return null
        failSrl?.let { (prefix, e) -> if (url.startsWith(prefix)) throw e }
        val key = "srl-" + sha1Hex(url)
        cache.put(key, null) { it.write(url.toByteArray()) }
        return key
    }
}
