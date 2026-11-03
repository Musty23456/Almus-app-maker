package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.AiAvailability
import kotlinx.coroutines.flow.Flow

/**
 * Phase 9: vocabulary of the Gemini integration. Plain Kotlin (no Android classes), so it is unit-tested on a normal
 * JVM. The app never talks to Google: it talks to the ALMUS backend ([AiBackend]), which holds the Gemini API key.
 */
enum class AiRole(val wire: String) { USER("user"), MODEL("model") }

data class AiMessage(val role: AiRole, val content: String)

/** The kind of work a request is for. The backend maps each name to its own reviewed instructions. */
enum class AiTaskKind(val wire: String, val expectsJson: Boolean) {
    CHAT("chat", false),
    EXPLAIN("explain", false),
    GENERATE_CODE("generate_code", false),
    EDIT_CODE("edit_code", false),
    PLAN_PROJECT("plan_project", true),
    GENERATE_PROJECT("generate_project", true),
    EDIT_PROJECT_SELECT("edit_project_select", true),
    EDIT_PROJECT("edit_project", true),
    DEBUG("debug", true),
    REVIEW("review", true),
}

data class AiRequest(val task: AiTaskKind, val messages: List<AiMessage>, val model: String? = null) {
    val totalChars: Int get() = messages.sumOf { it.content.length }
}

data class AiUsage(val promptTokens: Int, val outputTokens: Int, val totalTokens: Int)

data class AiCompletion(
    val text: String,
    val model: String?,
    val finishReason: String?,
    val usage: AiUsage?,
    /** How many secret-looking values the backend removed from the request. */
    val redactions: Int = 0,
) {
    /** The model ran out of output space: JSON answers are cut off and cannot be used. */
    val truncated: Boolean get() = finishReason == "MAX_TOKENS"
}

sealed interface AiStreamEvent {
    data class Started(val model: String?, val redactions: Int) : AiStreamEvent
    data class Delta(val text: String) : AiStreamEvent
    data class Finished(val finishReason: String?, val usage: AiUsage?) : AiStreamEvent
}

data class AiModelInfo(val id: String)
data class AiModelList(val default: String?, val models: List<AiModelInfo>)

enum class AiErrorKind {
    NOT_CONFIGURED, UNAUTHORIZED, RATE_LIMITED, UNAVAILABLE, TIMEOUT, BLOCKED, BAD_REQUEST, TOO_LARGE, SERVER_CONFIG, NETWORK, BAD_RESPONSE, CANCELLED,
}

/** Every failure of the AI layer. [message] is written for the user and never contains credentials. */
class AiException(
    val kind: AiErrorKind,
    message: String,
    val retryable: Boolean = false,
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The ALMUS backend, as the app sees it. */
interface AiBackend {
    suspend fun availability(): AiAvailability
    suspend fun models(): AiModelList

    /** One answer in one piece. Throws [AiException]. */
    suspend fun complete(request: AiRequest): AiCompletion

    /** Streaming answer: Started, Delta*, Finished. The flow throws [AiException]. Cancelling the collector cancels the request. */
    fun stream(request: AiRequest): Flow<AiStreamEvent>
}

/**
 * Collects a streamed answer into one [AiCompletion], reporting the number of characters received so far.
 * Long structured answers (whole projects) use streaming too, so the connection never looks idle.
 */
suspend fun AiBackend.completeStreaming(request: AiRequest, onProgress: (Int) -> Unit = {}): AiCompletion {
    val sb = StringBuilder()
    var model: String? = null
    var redactions = 0
    var finish: String? = null
    var usage: AiUsage? = null
    var finished = false
    stream(request).collect { ev ->
        when (ev) {
            is AiStreamEvent.Started -> { model = ev.model; redactions = ev.redactions }
            is AiStreamEvent.Delta -> { sb.append(ev.text); onProgress(sb.length) }
            is AiStreamEvent.Finished -> { finish = ev.finishReason; usage = ev.usage; finished = true }
        }
    }
    if (!finished) throw AiException(AiErrorKind.NETWORK, "The connection ended before the answer was complete.", retryable = true)
    return AiCompletion(sb.toString(), model, finish, usage, redactions)
}
