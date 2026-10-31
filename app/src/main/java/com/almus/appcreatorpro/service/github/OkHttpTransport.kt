package com.almus.appcreatorpro.service.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Real network transport (OkHttp, because Android's HttpURLConnection cannot send PATCH, which GitHub needs
 * to move a branch). Does NOT install any logging interceptor: request headers are never written anywhere.
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.toRequestBody(request.contentType.toMediaType())
        val needsBody = request.method in setOf("POST", "PUT", "PATCH")
        builder.method(request.method, body ?: if (needsBody) "".toRequestBody(null) else null)
        client.newCall(builder.build()).execute().use { response ->
            val headers = HashMap<String, String>()
            for (name in response.headers.names()) {
                headers[name.lowercase()] = response.headers[name].orEmpty()
            }
            HttpResponse(code = response.code, headers = headers, body = response.body?.string().orEmpty())
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Redirects (e.g. job log downloads) are followed; OkHttp drops the Authorization header
            // when a redirect leaves api.github.com, so the token is never sent to a storage host.
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
