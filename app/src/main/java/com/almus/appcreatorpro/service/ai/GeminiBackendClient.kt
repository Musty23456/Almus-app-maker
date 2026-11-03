package com.almus.appcreatorpro.service.ai

import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiBackendConfig
import com.almus.appcreatorpro.ai.AiCompletion
import com.almus.appcreatorpro.ai.AiErrorKind
import com.almus.appcreatorpro.ai.AiException
import com.almus.appcreatorpro.ai.AiModelInfo
import com.almus.appcreatorpro.ai.AiModelList
import com.almus.appcreatorpro.ai.AiRequest
import com.almus.appcreatorpro.ai.AiSettingsStore
import com.almus.appcreatorpro.ai.AiStreamEvent
import com.almus.appcreatorpro.ai.AiUsage
import com.almus.appcreatorpro.ai.SseEvent
import com.almus.appcreatorpro.ai.SseEventReader
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.service.artifacts.StreamResponse
import com.almus.appcreatorpro.service.github.HttpRequest
import com.almus.appcreatorpro.service.github.HttpResponse
import com.almus.appcreatorpro.service.github.HttpTransport
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.arr
import com.almus.appcreatorpro.service.github.asObj
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.obj
import com.almus.appcreatorpro.service.github.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Talks to the ALMUS backend (see /backend). Signs in with the user's access code, keeps the short-lived session token
 * in memory only, and signs in again once when the backend says the session expired.
 *
 * Nothing here knows a Gemini API key: the backend holds it. Requests carry only the session token.
 */
class GeminiBackendClient(
    private val settings: AiSettingsStore,
    private val http: HttpTransport,
    private val streams: PostStreamTransport,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : AiBackend {

    private val loginLock = Mutex()
    private var token: String? = null
    private var tokenExpiresAt: Long = 0L
    private var tokenFor: String? = null

    override suspend fun availability(): AiAvailability =
        if (settings.config() == null) AiAvailability.NotReady("The AI assistant is not set up yet. Open AI settings and enter your backend address and access code.")
        else AiAvailability.Ready

    private fun configOrThrow(): AiBackendConfig =
        settings.config() ?: throw AiException(AiErrorKind.NOT_CONFIGURED, "The AI assistant is not set up yet. Open AI settings and enter your backend address and access code.")

    // ---- session ------------------------------------------------------------------------------------------------

    private suspend fun ensureToken(config: AiBackendConfig, forceNew: Boolean = false): String = loginLock.withLock {
        val cached = token
        // A token is tied to the backend it came from; changing the address or code in settings must not reuse it.
        val identity = config.baseUrl + "|" + config.accessCode.hashCode()
        if (!forceNew && cached != null && tokenFor == identity && clockMillis() < tokenExpiresAt - EXPIRY_MARGIN_MS) return@withLock cached
        val response = send(HttpRequest("POST", config.baseUrl + "/v1/auth/login", body = MiniJson.stringify(mapOf("accessCode" to config.accessCode))))
        if (!response.isSuccess) throw parseError(response.code, response.body, response.header("retry-after"))
        val obj = try { MiniJson.parseObject(response.body) } catch (e: Exception) { throw badResponse() }
        val newToken = obj.str("token") ?: throw badResponse()
        val ttl = obj.long("expiresInSeconds") ?: 600L
        token = newToken
        tokenFor = identity
        tokenExpiresAt = clockMillis() + ttl * 1000L
        newToken
    }

    private fun invalidateToken() { token = null; tokenExpiresAt = 0L }

    /** Runs [call] with a valid token. One automatic sign-in retry when the backend answers "unauthorized". */
    private suspend fun <T> withSession(call: suspend (config: AiBackendConfig, token: String) -> T): T {
        val config = configOrThrow()
        var t = ensureToken(config)
        try {
            return call(config, t)
        } catch (e: AiException) {
            if (e.kind != AiErrorKind.UNAUTHORIZED) throw e
        }
        invalidateToken()
        t = ensureToken(config, forceNew = true)
        return call(config, t)
    }

    // ---- API ----------------------------------------------------------------------------------------------------

    override suspend fun models(): AiModelList = withSession { config, t ->
        val response = send(HttpRequest("GET", config.baseUrl + "/v1/models", headers = bearer(t)))
        if (!response.isSuccess) throw parseError(response.code, response.body, response.header("retry-after"))
        val obj = try { MiniJson.parseObject(response.body) } catch (e: Exception) { throw badResponse() }
        val models = obj.arr("models").orEmpty().mapNotNull { it.asObj()?.str("id") }.map { AiModelInfo(it) }
        AiModelList(obj.str("default"), models)
    }

    override suspend fun complete(request: AiRequest): AiCompletion = withSession { config, t ->
        val response = send(HttpRequest("POST", config.baseUrl + "/v1/generate", headers = bearer(t), body = bodyOf(request)))
        if (!response.isSuccess) throw parseError(response.code, response.body, response.header("retry-after"))
        val obj = try { MiniJson.parseObject(response.body) } catch (e: Exception) { throw badResponse() }
        val text = obj.str("text") ?: throw badResponse()
        AiCompletion(
            text = text,
            model = obj.str("model"),
            finishReason = obj.str("finishReason"),
            usage = usageOf(obj.obj("usage")),
            redactions = obj.long("redactions")?.toInt() ?: 0,
        )
    }

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = channelFlow {
        val config = configOrThrow()
        val body = bodyOf(request)
        var forceNew = false
        for (attempt in 0..1) {
            val progress = StreamProgress() // per call: two streams at the same time must not share it
            val t = ensureToken(config, forceNew)
            try {
                readStream(config, t, body, progress)
                return@channelFlow
            } catch (e: AiException) {
                // Only an "unauthorized" before the first event is retried, once, after signing in again.
                if (e.kind == AiErrorKind.UNAUTHORIZED && attempt == 0 && !progress.started) { invalidateToken(); forceNew = true; continue }
                throw e
            }
        }
    }

    private class StreamProgress { var started = false }

    private suspend fun ProducerScope<AiStreamEvent>.readStreamEvents(resp: StreamResponse, progress: StreamProgress) {
        if (resp.code !in 200..299) {
            val text = try { resp.body.readBytes().toString(Charsets.UTF_8) } catch (e: IOException) { "" }
            throw parseError(resp.code, text, resp.header("retry-after"))
        }
        val reader = resp.body.bufferedReader(Charsets.UTF_8)
        val parser = SseEventReader()
        var finished = false
        suspend fun handle(ev: SseEvent) {
            progress.started = true
            val obj = try { MiniJson.parseObject(ev.data) } catch (e: Exception) { throw badResponse() }
            when (ev.event) {
                "meta" -> send(AiStreamEvent.Started(obj.str("model"), obj.long("redactions")?.toInt() ?: 0))
                "delta" -> send(AiStreamEvent.Delta(obj.str("text").orEmpty()))
                "done" -> { finished = true; send(AiStreamEvent.Finished(obj.str("finishReason"), usageOf(obj.obj("usage")))) }
                "error" -> throw errorFromBody(obj)
            }
        }
        while (true) {
            val line = reader.readLine() ?: break
            val ev = parser.feedLine(line) ?: continue
            handle(ev)
        }
        parser.finish()?.let { handle(it) }
        if (!finished) throw AiException(AiErrorKind.NETWORK, "The connection ended before the answer was complete.", retryable = true)
    }

    private suspend fun ProducerScope<AiStreamEvent>.readStream(config: AiBackendConfig, t: String, body: String, progress: StreamProgress) {
        try {
            streams.post(config.baseUrl + "/v1/generate/stream", bearer(t) + mapOf("Accept" to "text/event-stream"), body) { resp ->
                readStreamEvents(resp, progress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw networkError(e)
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun bearer(t: String): Map<String, String> = mapOf("Authorization" to "Bearer $t", "Accept" to "application/json")

    private fun bodyOf(request: AiRequest): String {
        val map = LinkedHashMap<String, Any?>()
        map["task"] = request.task.wire
        val model = request.model ?: settings.selectedModel()
        if (!model.isNullOrBlank()) map["model"] = model
        map["messages"] = request.messages.map { mapOf("role" to it.role.wire, "content" to it.content) }
        return MiniJson.stringify(map)
    }

    private suspend fun send(request: HttpRequest): HttpResponse = try {
        http.execute(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw networkError(e)
    }

    private fun networkError(e: IOException): AiException =
        if (e is SocketTimeoutException) AiException(AiErrorKind.TIMEOUT, "The AI backend did not answer in time. Try again.", retryable = true, cause = e)
        else AiException(AiErrorKind.NETWORK, "Could not reach the AI backend. Check the address in AI settings and your connection.", retryable = true, cause = e)

    private fun badResponse() = AiException(AiErrorKind.BAD_RESPONSE, "The AI backend sent an answer the app does not understand. Check that the backend address is correct.")

    private fun usageOf(o: Map<String, Any?>?): AiUsage? {
        o ?: return null
        return AiUsage((o.long("promptTokens") ?: 0L).toInt(), (o.long("outputTokens") ?: 0L).toInt(), (o.long("totalTokens") ?: 0L).toInt())
    }

    private fun errorFromBody(err: Map<String, Any?>): AiException {
        val code = err.str("code").orEmpty()
        val message = err.str("message").orEmpty().ifBlank { "The AI request failed." }
        val retryable = err["retryable"] as? Boolean ?: false
        val retryAfter = err.long("retryAfterSeconds")?.toInt()
        val kind = when (code) {
            "unauthorized", "invalid_access_code" -> AiErrorKind.UNAUTHORIZED
            "rate_limited", "daily_limit", "upstream_rate_limited", "too_many_attempts" -> AiErrorKind.RATE_LIMITED
            "upstream_unavailable", "upstream_empty", "upstream_bad_response" -> AiErrorKind.UNAVAILABLE
            "upstream_timeout" -> AiErrorKind.TIMEOUT
            "blocked" -> AiErrorKind.BLOCKED
            "payload_too_large" -> AiErrorKind.TOO_LARGE
            "upstream_auth", "upstream_model" -> AiErrorKind.SERVER_CONFIG
            "bad_request", "bad_model", "upstream_rejected", "unsupported_media_type" -> AiErrorKind.BAD_REQUEST
            else -> AiErrorKind.UNAVAILABLE
        }
        return AiException(kind, message, retryable, retryAfter)
    }

    /** Turns an HTTP error answer into an [AiException]. A non-JSON body (a proxy's error page) is never shown to the user. */
    internal fun parseError(status: Int, body: String, retryAfterHeader: String?): AiException {
        val parsed = try { MiniJson.parseObject(body).obj("error") } catch (e: Exception) { null }
        if (parsed != null) {
            val ex = errorFromBody(parsed)
            if (ex.retryAfterSeconds == null && retryAfterHeader?.toIntOrNull() != null) {
                return AiException(ex.kind, ex.message.orEmpty(), ex.retryable, retryAfterHeader.toInt())
            }
            return ex
        }
        return when (status) {
            401 -> AiException(AiErrorKind.UNAUTHORIZED, "Sign-in to the AI backend failed. Check your access code in AI settings.")
            413 -> AiException(AiErrorKind.TOO_LARGE, "The request is too large. Send fewer or smaller files.")
            429 -> AiException(AiErrorKind.RATE_LIMITED, "Too many requests. Wait a moment and try again.", true, retryAfterHeader?.toIntOrNull())
            in 500..599 -> AiException(AiErrorKind.UNAVAILABLE, "The AI backend is not available right now (HTTP $status). Try again later.", true)
            else -> AiException(AiErrorKind.BAD_RESPONSE, "The AI backend answered with HTTP $status. Check that the backend address is correct.")
        }
    }

    private companion object {
        const val EXPIRY_MARGIN_MS = 30_000L
    }
}
