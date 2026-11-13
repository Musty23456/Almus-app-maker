package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.debug.AiDebugProvider
import com.almus.appcreatorpro.debug.AiFailures
import com.almus.appcreatorpro.debug.AiDebugRequest
import com.almus.appcreatorpro.debug.AiPromptRenderer
import com.almus.appcreatorpro.debug.AiResponseParser
import com.almus.appcreatorpro.debug.AiResult
import com.almus.appcreatorpro.debug.AiFollowUpRequest
import com.almus.appcreatorpro.debug.FollowUpParser
import com.almus.appcreatorpro.debug.FollowUpPrompt
import com.almus.appcreatorpro.debug.FollowUpResult

/**
 * Connects the Phase 8 debugging system to Gemini. The request was already built, redacted and confirmed by the user in
 * the debug screen; this class only renders it, sends it and parses the answer. Patches in the answer become normal
 * fix suggestions (checked against the real files, previewed, applied or rejected, undoable) in `DebugSession`.
 */
class GeminiDebugProvider(private val backend: AiBackend) : AiDebugProvider {
    override val id: String = "gemini-backend"
    override val displayName: String = "Gemini (ALMUS backend)"

    override suspend fun availability(): AiAvailability = backend.availability()

    /**
     * Never throws for a failed request, only for cancellation. Besides [AiException] (already worded for the user by
     * the backend client) it also catches everything else the network, keystore or JSON code can throw, and an empty
     * or cut-off answer is reported as such instead of as a parse error.
     */
    override suspend fun analyze(request: AiDebugRequest): AiResult = try {
        val prompt = AiPromptRenderer.render(request)
        val c = backend.completeStreaming(AiRequest(AiTaskKind.DEBUG, listOf(AiMessage(AiRole.USER, prompt))))
        if (c.text.isBlank()) {
            AiResult.Failed(if (c.truncated) CUT_OFF else "The AI returned an empty answer. Try again.", retryable = true)
        } else {
            when (val p = AiResponseParser.parse(c.text, c.model)) {
                is AiResponseParser.Parsed.Ok -> AiResult.Success(p.response)
                is AiResponseParser.Parsed.Bad -> AiResult.Failed(if (c.truncated) CUT_OFF else "The AI answer could not be read: ${p.reason} Try again.", retryable = true)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: AiException) {
        if (e.kind == AiErrorKind.NOT_CONFIGURED) AiResult.Unavailable(e.message.orEmpty())
        else AiResult.Failed(e.message.orEmpty() + (e.retryAfterSeconds?.let { " Wait about $it seconds." } ?: ""), e.retryable)
    } catch (e: Throwable) {
        if (!AiFailures.isRecoverable(e)) throw e
        AiFailures.failed(e)
    }

    /** Phase 22: same error handling as [analyze]; only the task name, prompt and parser differ. */
    override suspend fun followUp(request: AiFollowUpRequest): FollowUpResult = try {
        val prompt = FollowUpPrompt.render(request)
        val c = backend.completeStreaming(AiRequest(AiTaskKind.DEBUG_FOLLOWUP, listOf(AiMessage(AiRole.USER, prompt))))
        if (c.text.isBlank()) {
            FollowUpResult.Failed(if (c.truncated) CUT_OFF else "The AI returned an empty answer. Try again.", retryable = true)
        } else {
            when (val p = FollowUpParser.parse(c.text, c.model)) {
                is FollowUpParser.Parsed.Ok -> FollowUpResult.Success(p.answer)
                is FollowUpParser.Parsed.Bad -> FollowUpResult.Failed(if (c.truncated) CUT_OFF else "The AI answer could not be read: ${p.reason} Try again.", retryable = true)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: AiException) {
        if (e.kind == AiErrorKind.NOT_CONFIGURED) FollowUpResult.Unavailable(e.message.orEmpty())
        else FollowUpResult.Failed(e.message.orEmpty() + (e.retryAfterSeconds?.let { " Wait about $it seconds." } ?: ""), e.retryable)
    } catch (e: Throwable) {
        if (!AiFailures.isRecoverable(e)) throw e
        AiFailures.describe(e).let { (message, retry) -> FollowUpResult.Failed(message, retry, e) }
    }

    private companion object {
        const val CUT_OFF = "The answer was cut off because it was too long. Try with fewer errors."
    }
}
