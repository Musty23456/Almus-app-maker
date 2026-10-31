package com.almus.appcreatorpro.service.github

/**
 * One HTTP request. [headers] may contain an Authorization token, so [toString] deliberately prints
 * only the method and URL: a request can never leak a token through logs, exceptions or test output.
 */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    /** Request body media type, e.g. application/json. */
    val contentType: String = "application/json",
) {
    override fun toString(): String = "HttpRequest($method $url)"
}

/** Response with lower-cased header names. [toString] omits body and headers. */
class HttpResponse(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    fun header(name: String): String? = headers[name.lowercase()]
    val isSuccess: Boolean get() = code in 200..299
    override fun toString(): String = "HttpResponse($code)"
}

/** Network seam. Implementations throw [java.io.IOException] for connectivity problems; any HTTP status is a normal response. */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}
