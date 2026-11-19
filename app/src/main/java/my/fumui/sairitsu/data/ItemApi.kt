package my.fumui.sairitsu.data

import my.fumui.sairitsu.data.model.ServerJson
import my.fumui.sairitsu.gameplay.withCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Where to continue a list: a page index, or the cursor the server sent. */
sealed interface PageToken {
    data class Page(val index: Int) : PageToken
    data class Cursor(val value: String) : PageToken
}

/** One page of a list; [next] is null on the last page. */
data class ItemPage(val items: List<JsonObject>, val next: PageToken?)

/** Lists and details of any item type (`posts`, `skins`, ...) on one server. */
class ItemApi(private val server: ServerAddress, private val client: OkHttpClient) {
    /** `GET sonolus/{type}/list`; null [token] is the first page. */
    suspend fun list(type: String, token: PageToken?): ItemPage {
        val url = url(type, "list").newBuilder().apply {
            when (token) {
                is PageToken.Page -> addQueryParameter("page", "${token.index}")
                is PageToken.Cursor -> addQueryParameter("cursor", token.value)
                null -> Unit
            }
        }.build()
        val body = get(url)
        val items = (body["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val pageCount = (body["pageCount"] as? JsonPrimitive)?.intOrNull ?: 1
        val cursor = (body["cursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val next = if (cursor != null || pageCount < 0) {
            if (items.isEmpty()) null else cursor?.let(PageToken::Cursor)
        } else {
            val index = (token as? PageToken.Page)?.index ?: 0
            if (index + 1 < pageCount) PageToken.Page(index + 1) else null
        }
        return ItemPage(items, next)
    }

    /** `GET sonolus/{type}/{name}`, the whole response object. */
    suspend fun details(type: String, name: String): JsonObject = get(url(type, name))

    private fun url(type: String, last: String): HttpUrl =
        server.baseUrl.newBuilder().addPathSegment("sonolus").addPathSegment(type).addPathSegment(last).build()

    /** The JSON object at [url]; an HTTP error is a [ServerApiException], any other body an [IOException]. */
    private suspend fun get(url: HttpUrl): JsonObject = withContext(Dispatchers.IO) {
        val body = withCall(client.newCall(Request.Builder().url(url).build())) { response ->
            if (!response.isSuccessful) throw ServerApiException(response.code, "HTTP ${response.code} for $url")
            response.body!!.string()
        }
        try {
            ServerJson.parseToJsonElement(body).jsonObject
        } catch (e: SerializationException) {
            throw IOException("Unreadable response from $url", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unreadable response from $url", e)
        }
    }
}
