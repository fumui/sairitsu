package my.fumui.sairitsu.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A level server's base URL, such as `https://example.com/horizon`. */
data class ServerAddress(val baseUrl: HttpUrl) {
    /** The address as links write it: host, optional port and path, without the scheme. */
    val linkAddress: String
        get() {
            val port = if (baseUrl.port == HttpUrl.defaultPort(baseUrl.scheme)) "" else ":${baseUrl.port}"
            return baseUrl.host + port + baseUrl.encodedPath.trimEnd('/')
        }

    /** Resolves a resource URL the server sent; paths starting with `/` are relative to the server address. */
    fun resolve(url: String?): String? =
        if (url != null && url.startsWith("/")) baseUrl.toString().trimEnd('/') + url else url

    companion object {
        fun parse(input: String): ServerAddress? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme =
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if ('.' !in url.host && url.host != "localhost") return null
            return ServerAddress(url)
        }
    }
}
