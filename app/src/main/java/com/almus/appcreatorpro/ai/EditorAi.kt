package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.editor.Language
import com.almus.appcreatorpro.editor.LineIndex
import com.almus.appcreatorpro.editor.OutlineEngine

/** What the editor knows about the place the user is working at. Offsets are character offsets into [text]. */
data class EditorAiContext(
    val path: String,
    val text: String,
    val selStart: Int,
    val selEnd: Int,
    val projectName: String? = null,
) {
    val language: Language get() = Language.fromFileName(path)
}

/**
 * Phase 11: contextual AI help inside the editor, built on the Phase 9 backend. This class only BUILDS requests and reads
 * answers; sending goes through the existing [AiBackend]. Rules it enforces before anything leaves the phone:
 *  - files that look like they hold secrets (keystores, .env, credentials) are never sent ([SecretScanner.isSensitivePath]);
 *  - secret-looking values inside the code are replaced locally ([SecretScanner.redact]) in addition to the backend doing it;
 *  - only a window around the caret and at most [MAX_SELECTION_CHARS] of selected text are sent, never the whole file.
 * Suggestions are never applied here: the caller shows them and the user decides.
 */
object EditorAiPrompts {
    const val MAX_SELECTION_CHARS = 6000
    const val WINDOW_LINES = 30
    const val MAX_OUTLINE_ITEMS = 40
    const val CURSOR_MARKER = "<CURSOR>"

    /** Null when the file may be sent, otherwise the reason it may not. */
    fun blockedReason(ctx: EditorAiContext): String? = when {
        SecretScanner.isSensitivePath(ctx.path) -> "This file looks like it holds secrets, so it is never sent to the AI."
        ctx.text.isBlank() -> "The file is empty."
        else -> null
    }

    fun explain(ctx: EditorAiContext, question: String? = null): AiRequest {
        val ask = question?.trim().orEmpty()
        val intro = if (selectionOf(ctx).isNotEmpty()) {
            "Explain the SELECTED code clearly for a developer who has not seen it. Say what it does, anything surprising, and possible bugs."
        } else {
            "Explain what the code around the CURSOR does. Say what it does, anything surprising, and possible bugs."
        }
        val text = buildString {
            append(intro).append('\n')
            if (ask.isNotEmpty()) append("The developer asks: ").append(ask.take(1000)).append('\n')
            append("Only describe what is in the code shown. If something depends on code you cannot see, say so instead of guessing.\n\n")
            append(contextBlock(ctx))
        }
        return AiRequest(AiTaskKind.EXPLAIN, listOf(AiMessage(AiRole.USER, text)))
    }

    /** With a selection the model rewrites the selection; without one it writes code to insert at the cursor. */
    fun suggest(ctx: EditorAiContext, instruction: String? = null): AiRequest {
        val hasSelection = selectionOf(ctx).isNotEmpty()
        val ask = instruction?.trim().orEmpty()
        val text = buildString {
            if (hasSelection) {
                append("Rewrite ONLY the SELECTED code")
                append(if (ask.isNotEmpty()) " as requested: ${ask.take(1000)}" else " to improve it without changing what it does")
                append(".\nReply with ONE fenced code block that contains the replacement for the selection and nothing else outside the block.\n")
            } else {
                append("Write the code that belongs at $CURSOR_MARKER")
                append(if (ask.isNotEmpty()) ": ${ask.take(1000)}" else ", continuing the code naturally")
                append(".\nReply with ONE fenced code block that contains only the code to insert at the cursor, nothing else outside the block.\n")
            }
            append("Match the file's style, language and indentation. Do not invent functions that are not shown; prefer the standard library.\n\n")
            append(contextBlock(ctx))
        }
        return AiRequest(if (hasSelection) AiTaskKind.EDIT_CODE else AiTaskKind.GENERATE_CODE, listOf(AiMessage(AiRole.USER, text)))
    }

    fun selectionOf(ctx: EditorAiContext): String {
        val a = minOf(ctx.selStart, ctx.selEnd).coerceIn(0, ctx.text.length)
        val b = maxOf(ctx.selStart, ctx.selEnd).coerceIn(0, ctx.text.length)
        return ctx.text.substring(a, b)
    }

    internal fun contextBlock(ctx: EditorAiContext): String {
        val text = ctx.text
        val index = LineIndex(text)
        val a = minOf(ctx.selStart, ctx.selEnd).coerceIn(0, text.length)
        val b = maxOf(ctx.selStart, ctx.selEnd).coerceIn(0, text.length)
        val firstLine = (index.lineOf(a) - WINDOW_LINES).coerceAtLeast(0)
        val lastLine = (index.lineOf(b) + WINDOW_LINES).coerceAtMost(index.lineCount - 1)
        val start = index.lineStart(firstLine)
        val end = index.lineEnd(lastLine)
        val selection = text.substring(a, b)
        val sb = StringBuilder()
        sb.append("FILE: ").append(ctx.path).append('\n')
        sb.append("LANGUAGE: ").append(ctx.language.displayName).append('\n')
        if (!ctx.projectName.isNullOrBlank()) sb.append("PROJECT: ").append(ctx.projectName).append('\n')
        val names = OutlineEngine.outline(text, ctx.language).take(MAX_OUTLINE_ITEMS).map { it.name }
        if (names.isNotEmpty()) sb.append("DECLARATIONS IN THIS FILE: ").append(names.joinToString(", ")).append('\n')
        sb.append("LINES ").append(firstLine + 1).append('-').append(lastLine + 1).append(" OF ").append(index.lineCount).append(":\n")
        val marked = if (selection.isEmpty()) {
            text.substring(start, a) + CURSOR_MARKER + text.substring(a, end)
        } else {
            val shown = if (selection.length > MAX_SELECTION_CHARS) selection.substring(0, MAX_SELECTION_CHARS) else selection
            text.substring(start, a) + "<SELECTION>" + shown + "</SELECTION>" + text.substring(minOf(b, end).coerceAtLeast(a), end)
        }
        sb.append("```").append('\n').append(SecretScanner.redact(marked).text).append('\n').append("```")
        return sb.toString()
    }

    private val FENCE = Regex("```[\\w+#.-]*[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n?```")

    /** The code of the first COMPLETE fenced block in an answer, or null (no block, or the answer was cut off inside one). */
    fun extractCode(answer: String): String? {
        val m = FENCE.find(answer) ?: return null
        val code = m.groupValues[1].trimEnd('\n', '\r')
        return code.ifBlank { null }
    }

    /** Sends one request and returns the answer text. Expected failures become [AiStep.Failed]; cancellation is not swallowed. */
    suspend fun ask(backend: AiBackend, request: AiRequest): AiStep<String> = try {
        val c = backend.complete(request)
        if (c.truncated) AiStep.Failed("The answer was cut off. Select less code and try again.", retryable = true)
        else if (c.text.isBlank()) AiStep.Failed("The AI returned an empty answer. Try again.", retryable = true)
        else AiStep.Ok(c.text)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: AiException) {
        AiStep.Failed(e.message.orEmpty(), e.retryable)
    } catch (e: Exception) {
        AiStep.Failed("The AI answer could not be processed (${e.javaClass.simpleName}). Try again.", retryable = true)
    }
}
