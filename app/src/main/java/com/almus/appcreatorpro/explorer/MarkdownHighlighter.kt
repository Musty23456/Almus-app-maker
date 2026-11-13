package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.editor.Token
import com.almus.appcreatorpro.editor.TokenType

/**
 * Light Markdown coloring for the preview (the editor's tokenizer leaves Markdown plain). Reuses the existing token
 * types: headings = KEYWORD, code = STRING, quotes = COMMENT, list markers = ANNOTATION, links = TAG.
 * Tokens never overlap and come in order.
 */
object MarkdownHighlighter {
    private val orderedMarker = Regex("^\\s*\\d{1,3}[.)] ")

    fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var inFence = false
        var lineStart = 0
        val n = text.length
        while (lineStart <= n) {
            val nl = text.indexOf('\n', lineStart)
            val rawEnd = if (nl < 0) n else nl
            val end = if (rawEnd > lineStart && text[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
            val line = text.substring(lineStart, end)
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    if (end > lineStart) tokens += Token(lineStart, end, TokenType.STRING)
                    inFence = !inFence
                }
                inFence -> if (end > lineStart) tokens += Token(lineStart, end, TokenType.STRING)
                trimmed.startsWith("#") && isHeading(trimmed) -> tokens += Token(lineStart, end, TokenType.KEYWORD)
                trimmed.startsWith(">") -> tokens += Token(lineStart, end, TokenType.COMMENT)
                else -> inline(line, lineStart, tokens)
            }
            if (nl < 0) break
            lineStart = nl + 1
        }
        return tokens
    }

    private fun isHeading(trimmed: String): Boolean {
        val hashes = trimmed.takeWhile { it == '#' }.length
        return hashes in 1..6 && (trimmed.length == hashes || trimmed[hashes] == ' ')
    }

    private fun inline(line: String, offset: Int, out: MutableList<Token>) {
        var i = 0
        val marker = orderedMarker.find(line)
        if (marker != null) {
            val start = marker.value.length - marker.value.trimStart().length
            out += Token(offset + start, offset + marker.value.length - 1, TokenType.ANNOTATION)
            i = marker.value.length
        } else {
            val trimmed = line.trimStart()
            if (trimmed.length >= 2 && trimmed[0] in "-*+" && trimmed[1] == ' ') {
                val start = line.length - trimmed.length
                out += Token(offset + start, offset + start + 1, TokenType.ANNOTATION)
                i = start + 2
            }
        }
        while (i < line.length) {
            val c = line[i]
            if (c == '`') {
                val close = line.indexOf('`', i + 1)
                if (close > i) {
                    out += Token(offset + i, offset + close + 1, TokenType.STRING)
                    i = close + 1
                    continue
                }
            } else if (c == '[') {
                val mid = line.indexOf("](", i + 1)
                val close = if (mid > 0) line.indexOf(')', mid + 2) else -1
                if (mid > 0 && close > mid) {
                    out += Token(offset + i, offset + close + 1, TokenType.TAG)
                    i = close + 1
                    continue
                }
            }
            i++
        }
    }
}
