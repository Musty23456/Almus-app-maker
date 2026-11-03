package com.almus.appcreatorpro.service.ai

import com.almus.appcreatorpro.service.artifacts.StreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * POST whose response body is read as a stream (Server-Sent Events). The body stream is valid only inside [block].
 * Connectivity problems are thrown as [IOException]; any HTTP status is a normal response. Cancelling the calling
 * coroutine must abort the transfer.
 */
interface PostStreamTransport {
    suspend fun <T> post(url: String, headers: Map<String, String>, body: String, block: suspend (StreamResponse) -> T): T
}

/** OkHttp implementation. No logging interceptor is installed, so the Authorization header is never written anywhere. */
class OkHttpPostStreamTransport(
    base: OkHttpClient = OkHttpClient(),
) : PostStreamTransport {

    private val client: OkHttpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // The backend sends a keep-alive line every 15 s, so a silent connection means a dead one.
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun <T> post(url: String, headers: Map<String, String>, body: String, block: suspend (StreamResponse) -> T): T =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder().url(url)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            } catch (e: IllegalArgumentException) {
                throw IOException("Invalid backend address.")
            }
            val call = client.newCall(request)
            coroutineScope {
                val watcher = launch {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        val responseHeaders = HashMap<String, String>()
                        for (name in response.headers.names()) responseHeaders[name.lowercase()] = response.headers[name].orEmpty()
                        val stream = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                        block(StreamResponse(response.code, responseHeaders, stream))
                    }
                } finally {
                    watcher.cancel()
                }
            }
        }
}
