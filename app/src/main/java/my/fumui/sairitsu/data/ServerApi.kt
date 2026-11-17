package my.fumui.sairitsu.data

import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.data.model.ItemInfo
import my.fumui.sairitsu.data.model.ItemList
import my.fumui.sairitsu.data.model.ServerInfo
import my.fumui.sairitsu.data.model.ServerJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The level endpoints of a level server. */
interface LevelSource {
    suspend fun serverInfo(): ServerInfo
    suspend fun levelInfo(): ItemInfo
    suspend fun levelList(params: Map<String, String>, cursor: String?): ItemList
    suspend fun levelDetails(name: String): ItemDetails
}

class ServerApiException(val code: Int, message: String) : IOException(message)

class ServerApi(
    private val server: ServerAddress,
    private val client: OkHttpClient,
    private val session: () -> String? = { null },
) : LevelSource {
    override suspend fun serverInfo(): ServerInfo = get(url("sonolus", "info"), ServerInfo.serializer())

    override suspend fun levelInfo(): ItemInfo = get(url("sonolus", "levels", "info"), ItemInfo.serializer())

    override suspend fun levelList(params: Map<String, String>, cursor: String?): ItemList {
        val url = url("sonolus", "levels", "list").newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
            if (cursor != null) addQueryParameter("cursor", cursor)
        }.build()
        return get(url, ItemList.serializer())
    }

    override suspend fun levelDetails(name: String): ItemDetails =
        get(url("sonolus", "levels", name), ItemDetails.serializer())

    private fun url(vararg segments: String): HttpUrl =
        server.baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private suspend fun <T> get(url: HttpUrl, deserializer: DeserializationStrategy<T>): T {
        val request = Request.Builder().url(url).apply {
            session()?.let { header("Sonolus-Session", it) }
        }.build()
        val body = client.newCall(request).await()
        return withContext(Dispatchers.IO) { ServerJson.decodeFromString(deserializer, body) }
    }
}

private suspend fun Call.await(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!it.isSuccessful) {
                    continuation.resumeWithException(ServerApiException(it.code, "HTTP ${it.code} for ${call.request().url}"))
                    return
                }
                val result = runCatching { it.body!!.string() }
                result.fold(continuation::resume, continuation::resumeWithException)
            }
        }
    })
}
