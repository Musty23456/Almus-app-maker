package com.almus.appcreatorpro.editor

enum class TokenType { KEYWORD, STRING, COMMENT, NUMBER, ANNOTATION, TAG, ATTRIBUTE }

/** A highlighted range: [start] inclusive, [end] exclusive. */
data class Token(val start: Int, val end: Int, val type: TokenType)

/**
 * A small hand-written tokenizer. It is deliberately simple (no full grammar): good enough to color
 * keywords, strings, comments, numbers, annotations and markup, and fast enough to run on every edit.
 * Tokens never overlap and are returned in order.
 */
object SyntaxHighlighter {
    /** Above this size highlighting is skipped to keep typing responsive. */
    const val MAX_HIGHLIGHT_CHARS = 200_000

    fun tokenize(text: String, language: Language): List<Token> {
        if (text.length > MAX_HIGHLIGHT_CHARS) return emptyList()
        return when (language.family) {
            SyntaxFamily.C_LIKE -> tokenizeCLike(text, language)
            SyntaxFamily.MARKUP -> tokenizeMarkup(text)
            SyntaxFamily.NONE -> emptyList()
        }
    }

    private fun tokenizeCLike(text: String, lang: Language): List<Token> {
        val tokens = ArrayList<Token>()
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                lang.lineComment != null && text.startsWith(lang.lineComment, i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                    tokens += Token(i, end, TokenType.COMMENT)
                    i = end
                }
                lang.blockComments && text.startsWith("/*", i) -> {
                    val close = text.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    tokens += Token(i, end, TokenType.COMMENT)
                    i = end
                }
                lang.tripleQuotes && text.startsWith("\"\"\"", i) -> {
                    val close = text.indexOf("\"\"\"", i + 3)
                    val end = if (close < 0) n else close + 3
                    tokens += Token(i, end, TokenType.STRING)
                    i = end
                }
                c == '"' || c == '\'' || (c == '`' && lang.backtickStrings) -> {
                    val end = scanString(text, i, c)
                    tokens += Token(i, end, TokenType.STRING)
                    i = end
                }
                c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                    tokens += Token(i, j, TokenType.NUMBER)
                    i = j
                }
                c == '#' && lang.hexColors -> {
                    var j = i + 1
                    while (j < n && (text[j].isDigit() || text[j].lowercaseChar() in 'a'..'f')) j++
                    if (j > i + 1) tokens += Token(i, j, TokenType.NUMBER)
                    i = maxOf(j, i + 1)
                }
                c == '@' && lang.annotations -> {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    if (j > i + 1) tokens += Token(i, j, TokenType.ANNOTATION)
                    i = maxOf(j, i + 1)
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j++
                    if (text.substring(i, j) in lang.keywords) tokens += Token(i, j, TokenType.KEYWORD)
                    i = j
                }
                else -> i++
            }
        }
        return tokens
    }

    /** Returns the index just after the closing quote; unterminated strings end at the line end. */
    private fun scanString(text: String, start: Int, quote: Char): Int {
        var j = start + 1
        val n = text.length
        while (j < n) {
            val ch = text[j]
            when {
                ch == '\\' -> j += 2
                ch == quote -> return j + 1
                ch == '\n' && quote != '`' -> return j
                else -> j++
            }
        }
        return n
    }

    private fun tokenizeMarkup(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        val n = text.length
        var i = 0
        while (i < n) {
            if (text.startsWith("<!--", i)) {
                val close = text.indexOf("-->", i + 4)
                val end = if (close < 0) n else close + 3
                tokens += Token(i, end, TokenType.COMMENT)
                i = end
            } else if (text[i] == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] in "/!?")) {
                var j = i + 1
                if (text[j] in "/!?") j++
                while (j < n && (text[j].isLetterOrDigit() || text[j] in ":-_.")) j++
                tokens += Token(i, j, TokenType.TAG)
                while (j < n && text[j] != '>') {
                    val ch = text[j]
                    if (ch == '"' || ch == '\'') {
                        val close = text.indexOf(ch, j + 1)
                        val end = if (close < 0) n else close + 1
                        tokens += Token(j, end, TokenType.STRING)
                        j = end
                    } else if (ch.isLetter() || ch == '_' || ch == ':') {
                        var k = j + 1
                        while (k < n && (text[k].isLetterOrDigit() || text[k] in ":-_.")) k++
                        tokens += Token(j, k, TokenType.ATTRIBUTE)
                        j = k
                    } else {
                        j++
                    }
                }
                if (j < n) {
                    tokens += Token(j, j + 1, TokenType.TAG)
                    j++
                }
                i = j
            } else {
                i++
            }
        }
        return tokens
    }
}
