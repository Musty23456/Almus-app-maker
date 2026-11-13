package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.editor.Language
import com.almus.appcreatorpro.editor.SyntaxHighlighter
import com.almus.appcreatorpro.editor.Token

/** What the preview sheet shows for one file. Nothing here can change the file. */
data class FilePreview(
    val path: String,
    val kind: FileKind,
    val language: Language,
    /** The text shown (may be cut, see [truncated]); empty when [note] explains why there is none. */
    val text: String,
    val tokens: List<Token>,
    val shownLines: Int,
    val totalLines: Int,
    val sizeBytes: Long,
    val truncated: Boolean,
    /** Why there is no text (binary, too large, unreadable), or null. */
    val note: String? = null,
) {
    val hasText: Boolean get() = note == null
}

object FilePreviewBuilder {
    const val MAX_PREVIEW_LINES = 1_500
    const val MAX_PREVIEW_CHARS = 60_000

    fun build(path: String, fullText: String, sizeBytes: Long): FilePreview {
        val kind = FileTypes.recognize(path)
        val lines = fullText.split('\n')
        val totalLines = if (fullText.isEmpty()) 0 else lines.size
        val shown = StringBuilder()
        var count = 0
        for (line in lines) {
            if (count >= MAX_PREVIEW_LINES) break
            val extra = if (count == 0) line.length else line.length + 1
            if (shown.length + extra > MAX_PREVIEW_CHARS) break
            if (count > 0) shown.append('\n')
            shown.append(line)
            count++
        }
        val text = shown.toString()
        val truncated = count < lines.size && fullText.isNotEmpty()
        return FilePreview(
            path = path,
            kind = kind,
            language = kind.language,
            text = text,
            tokens = tokenize(text, kind),
            shownLines = if (text.isEmpty()) 0 else count,
            totalLines = totalLines,
            sizeBytes = sizeBytes,
            truncated = truncated,
        )
    }

    /** A preview with no text, only the file facts and the [reason]. */
    fun unavailable(path: String, sizeBytes: Long, reason: String): FilePreview {
        val kind = FileTypes.recognize(path)
        return FilePreview(path, kind, kind.language, "", emptyList(), 0, 0, sizeBytes, false, note = reason)
    }

    fun tokenize(text: String, kind: FileKind): List<Token> =
        if (kind == FileKind.MARKDOWN) MarkdownHighlighter.tokenize(text) else SyntaxHighlighter.tokenize(text, kind.language)
}
