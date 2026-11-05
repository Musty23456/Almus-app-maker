package com.almus.appcreatorpro.service.github

import com.almus.appcreatorpro.service.artifacts.StreamResponse
import com.almus.appcreatorpro.service.artifacts.StreamTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Streaming download transport (OkHttp). Redirects are NOT followed here: the downloader reads GitHub's redirect and
 * decides itself that the token is not sent to the storage host. No logging interceptor is installed.
 */
class OkHttpStreamTransport(
    base: OkHttpClient = OkHttpTransport.defaultClient(),
) : StreamTransport {

    private val client: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // large downloads: only the per-read timeout applies
        .build()

    override suspend fun <T> get(url: String, headers: Map<String, String>, block: suspend (StreamResponse) -> T): T =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
            } catch (e: IllegalArgumentException) {
                throw IOException("Invalid download address.")
            }
            val call = client.newCall(request)
            coroutineScope {
                // When the caller is cancelled, abort the blocking transfer instead of waiting for the next read timeout.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response ->
                        val responseHeaders = HashMap<String, String>()
                        for (name in response.headers.names()) responseHeaders[name.lowercase()] = response.headers[name].orEmpty()
                        val body = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                        block(StreamResponse(response.code, responseHeaders, body))
                    }
                } finally {
                    watcher.cancel()
                }
            }
        }
}
