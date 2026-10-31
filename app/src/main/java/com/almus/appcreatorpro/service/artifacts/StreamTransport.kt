package com.almus.appcreatorpro.service.artifacts

import java.io.InputStream

/**
 * One HTTP response whose body is read as a stream (artifact downloads can be large, so they are never held in memory).
 * [toString] prints only the status code: headers may carry sensitive values.
 */
class StreamResponse(
    val code: Int,
    /** Lower-cased header names. */
    val headers: Map<String, String>,
    val body: InputStream,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** Size announced by the server, if any. */
    val contentLength: Long? get() = header("content-length")?.toLongOrNull()?.takeIf { it >= 0L }

    override fun toString(): String = "StreamResponse($code)"
}

/**
 * Network seam for downloads.
 *
 * Contract: this transport NEVER follows redirects. The caller looks at the redirect itself, so it can decide which
 * headers (in particular the GitHub token) go to which host. The body stream is valid only inside [block]; the
 * transport closes it afterwards. Connectivity problems are thrown as [java.io.IOException]; any HTTP status is a
 * normal response. Cancelling the calling coroutine must abort a transfer that is in progress.
 */
interface StreamTransport {
    suspend fun <T> get(url: String, headers: Map<String, String>, block: suspend (StreamResponse) -> T): T
}
