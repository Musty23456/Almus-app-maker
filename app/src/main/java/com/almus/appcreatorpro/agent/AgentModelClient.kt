package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiCompletion
import com.almus.appcreatorpro.ai.AiErrorKind
import com.almus.appcreatorpro.ai.AiException
import com.almus.appcreatorpro.ai.AiMessage
import com.almus.appcreatorpro.ai.AiRequest
import com.almus.appcreatorpro.ai.AiStep
import com.almus.appcreatorpro.ai.AiStreamEvent
import com.almus.appcreatorpro.ai.AiTaskKind
import com.almus.appcreatorpro.ai.AiUsage
import kotlinx.coroutines.delay

/**
 * One place that talks to the model for the agent: streams the answer (progress and a live tail go to [onChunk]), retries
 * transient failures with a growing wait (honouring the server's retry-after, capped), counts every request against the
 * run's budget, and turns every failure into a message. Cancelling the calling coroutine cancels the request.
 */
class AgentModelClient(
    private val backend: AiBackend,
    private val perms: AgentPermissions,
    private val log: (AgentLogLevel, String) -> Unit = { _, _ -> },
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
) {
    /** Requests made in this run (retries included). */
    var calls: Int = 0
        private set

    fun restoreCalls(n: Int) { calls = n }

    suspend fun ask(task: AiTaskKind, messages: List<AiMessage>, onChunk: (Int, String) -> Unit = { _, _ -> }): AiStep<AiCompletion> {
        var attempt = 0
        while (true) {
            attempt++
            if (calls >= perms.maxModelCalls) {
                return AiStep.Failed("The agent reached its limit of ${perms.maxModelCalls} AI requests for one run. Review what it has done so far or start a smaller run.", retryable = false)
            }
            calls++
            try {
                return AiStep.Ok(collect(task, messages, onChunk))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AiException) {
                if (e.retryable && attempt <= perms.maxTransportRetries) {
                    val wait = backoffMs(e, attempt)
                    log(AgentLogLevel.WARN, "AI request failed (${e.message.orEmpty().take(120)}). Retry $attempt of ${perms.maxTransportRetries} in ${wait / 1000.0}s.")
                    waitMs(wait)
                    continue
                }
                return AiStep.Failed(e.message.orEmpty(), e.retryable)
            } catch (e: Exception) {
                return AiStep.Failed("The AI answer could not be processed (${e.javaClass.simpleName}). Try again.", retryable = true)
            }
        }
    }

    private suspend fun collect(task: AiTaskKind, messages: List<AiMessage>, onChunk: (Int, String) -> Unit): AiCompletion {
        val sb = StringBuilder()
        var model: String? = null
        var redactions = 0
        var finish: String? = null
        var usage: AiUsage? = null
        var finished = false
        backend.stream(AiRequest(task, messages)).collect { ev ->
            when (ev) {
                is AiStreamEvent.Started -> { model = ev.model; redactions = ev.redactions }
                is AiStreamEvent.Delta -> { sb.append(ev.text); onChunk(sb.length, sb.substring(maxOf(0, sb.length - TAIL_CHARS))) }
                is AiStreamEvent.Finished -> { finish = ev.finishReason; usage = ev.usage; finished = true }
            }
        }
        if (!finished) throw AiException(AiErrorKind.NETWORK, "The connection ended before the answer was complete.", retryable = true)
        return AiCompletion(sb.toString(), model, finish, usage, redactions)
    }

    private fun backoffMs(e: AiException, attempt: Int): Long {
        val hinted = e.retryAfterSeconds
        if (e.kind == AiErrorKind.RATE_LIMITED && hinted != null) return (hinted.coerceIn(1, MAX_HINTED_WAIT_S) * 1000L)
        return (BASE_WAIT_MS shl (attempt - 1)).coerceAtMost(MAX_WAIT_MS)
    }

    companion object {
        const val TAIL_CHARS = 300
        const val BASE_WAIT_MS = 1_000L
        const val MAX_WAIT_MS = 8_000L
        const val MAX_HINTED_WAIT_S = 30
    }
}
