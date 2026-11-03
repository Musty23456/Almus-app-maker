package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.debug.AiDebugProvider
import com.almus.appcreatorpro.debug.AiDebugRequest
import com.almus.appcreatorpro.debug.AiPromptRenderer
import com.almus.appcreatorpro.debug.AiResponseParser
import com.almus.appcreatorpro.debug.AiResult

/**
 * Connects the Phase 8 debugging system to Gemini. The request was already built, redacted and confirmed by the user in
 * the debug screen; this class only renders it, sends it and parses the answer. Patches in the answer become normal
 * fix suggestions (checked against the real files, previewed, applied or rejected, undoable) in `DebugSession`.
 */
class GeminiDebugProvider(private val backend: AiBackend) : AiDebugProvider {
    override val id: String = "gemini-backend"
    override val displayName: String = "Gemini (ALMUS backend)"

    override suspend fun availability(): AiAvailability = backend.availability()

    override suspend fun analyze(request: AiDebugRequest): AiResult = try {
        val prompt = AiPromptRenderer.render(request)
        val c = backend.completeStreaming(AiRequest(AiTaskKind.DEBUG, listOf(AiMessage(AiRole.USER, prompt))))
        when (val p = AiResponseParser.parse(c.text, c.model)) {
            is AiResponseParser.Parsed.Ok -> AiResult.Success(p.response)
            is AiResponseParser.Parsed.Bad -> AiResult.Failed(if (c.truncated) "The answer was cut off because it was too long. Try with fewer errors." else p.reason, retryable = true)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: AiException) {
        if (e.kind == AiErrorKind.NOT_CONFIGURED) AiResult.Unavailable(e.message.orEmpty()) else AiResult.Failed(e.message.orEmpty(), e.retryable)
    }
}
