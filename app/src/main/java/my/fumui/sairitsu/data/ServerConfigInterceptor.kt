package my.fumui.sairitsu.data

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds a server's saved configuration values to its API requests (info, lists and item details), never to
 * resource files. [configs] is linkAddress → query → value; a parameter already on the request wins.
 */
class ServerConfigInterceptor(private val configs: () -> Map<String, Map<String, String>>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val params = paramsFor(request.url)
        if (params.isEmpty()) return chain.proceed(request)
        val url = request.url.newBuilder().apply {
            params.forEach { (name, value) -> if (request.url.queryParameter(name) == null) addQueryParameter(name, value) }
        }.build()
        return chain.proceed(request.newBuilder().url(url).build())
    }

    private fun paramsFor(url: HttpUrl): Map<String, String> {
        val all = configs()
        if (all.isEmpty()) return emptyMap()
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        val path = url.host + port + url.encodedPath
        val key = all.keys.filter { path.startsWith("$it/") }.maxByOrNull { it.length } ?: return emptyMap()
        val rest = path.substring(key.length + 1).split('/')
        val isApi = rest[0] == "sonolus" && (rest == listOf("sonolus", "info") || (rest.size == 3 && rest[1] in ITEM_TYPES))
        return if (isApi) all.getValue(key) else emptyMap()
    }

    private companion object {
        val ITEM_TYPES = setOf(
            "posts", "playlists", "levels", "skins", "backgrounds", "effects",
            "particles", "engines", "replays", "rooms", "users",
        )
    }
}
