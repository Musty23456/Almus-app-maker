package com.almus.appcreatorpro.debug

/** Small text helpers shared by the static checks, the fix engine and editor navigation. */
object SourceText {

    /** Maps 1-based lines to character offsets and back. */
    class LineIndex(val text: String) {
        private val starts: IntArray

        init {
            val list = ArrayList<Int>()
            list.add(0)
            for (i in text.indices) if (text[i] == '\n') list.add(i + 1)
            starts = list.toIntArray()
        }

        val lineCount: Int get() = starts.size

        /** Offset of the first character of [line] (1-based); clamped into the text. */
        fun startOf(line: Int): Int = starts[(line - 1).coerceIn(0, starts.size - 1)]

        /** Offset just before the line break of [line] (or the end of the text). */
        fun endOf(line: Int): Int {
            val idx = (line - 1).coerceIn(0, starts.size - 1)
            val next = if (idx + 1 < starts.size) starts[idx + 1] - 1 else text.length
            return if (next > startOf(line) && text.getOrNull(next - 1) == '\r' && next <= text.length) next - 1 else next
        }

        /** 1-based line containing [offset]. */
        fun lineOf(offset: Int): Int {
            val o = offset.coerceIn(0, text.length)
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= o) lo = mid else hi = mid - 1
            }
            return lo + 1
        }
    }

    /**
     * Caret/selection range to show for a "go to line" request: the whole line when [column] is null, otherwise an
     * empty selection at that column. [line] is clamped to the text so a stale line number never crashes the editor.
     */
    fun selectionFor(text: String, line: Int?, column: Int?): IntRange {
        if (line == null) return 0..0
        val index = LineIndex(text)
        val l = line.coerceIn(1, index.lineCount)
        val start = index.startOf(l)
        val end = index.endOf(l)
        if (column == null || column < 1) return start..end
        val caret = (start + column - 1).coerceIn(start, end)
        return caret..caret
    }

    /**
     * Returns [text] with comments and the insides of string/char literals replaced by spaces (line breaks kept), so
     * regular expressions only see code. Offsets and line numbers stay the same. For Kotlin, `$name` and `${...}`
     * inside strings are kept because they are real references.
     */
    fun mask(text: String, kotlin: Boolean): String {
        val out = CharArray(text.length)
        var i = 0
        val n = text.length
        fun blank(from: Int, to: Int) {
            for (k in from until minOf(to, n)) out[k] = if (text[k] == '\n' || text[k] == '\r') text[k] else ' '
        }
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    var j = i
                    while (j < n && text[j] != '\n') j++
                    blank(i, j)
                    i = j
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    var depth = 1
                    var j = i + 2
                    while (j < n && depth > 0) {
                        if (text[j] == '/' && j + 1 < n && text[j + 1] == '*' && kotlin) { depth++; j += 2 }
                        else if (text[j] == '*' && j + 1 < n && text[j + 1] == '/') { depth--; j += 2 }
                        else j++
                    }
                    blank(i, j)
                    i = j
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val close = text.indexOf("\"\"\"", i + 3)
                    val end = if (close < 0) n else close + 3
                    out[i] = '"'; out[i + 1] = '"'; out[i + 2] = '"'
                    i = copyStringBody(text, out, i + 3, if (close < 0) n else close, kotlin)
                    if (close >= 0) { out[close] = '"'; out[close + 1] = '"'; out[close + 2] = '"' }
                    i = end
                }
                c == '"' -> {
                    out[i] = '"'
                    var j = i + 1
                    while (j < n && text[j] != '"' && text[j] != '\n') {
                        if (text[j] == '\\' && j + 1 < n) { out[j] = ' '; out[j + 1] = ' '; j += 2; continue }
                        if (kotlin && text[j] == '$' && j + 1 < n) {
                            if (text[j + 1] == '{') {
                                var depth = 1
                                var k = j + 2
                                while (k < n && depth > 0 && text[k] != '\n') {
                                    if (text[k] == '{') depth++ else if (text[k] == '}') depth--
                                    k++
                                }
                                for (m in j until k) out[m] = text[m]
                                j = k
                                continue
                            }
                            if (text[j + 1].isLetter() || text[j + 1] == '_') {
                                var k = j + 1
                                while (k < n && (text[k].isLetterOrDigit() || text[k] == '_')) k++
                                for (m in j until k) out[m] = text[m]
                                j = k
                                continue
                            }
                        }
                        out[j] = ' '
                        j++
                    }
                    if (j < n && text[j] == '"') { out[j] = '"'; j++ }
                    i = j
                }
                c == '\'' -> {
                    // A char literal: '\n', 'a', 'A'. Anything else (for example a lone quote) is left alone.
                    var j = i + 1
                    if (j < n && text[j] == '\\') j += 2 else j += 1
                    while (j < n && text[j] != '\'' && j - i < 8 && text[j] != '\n') j++
                    if (j < n && text[j] == '\'') {
                        out[i] = '\''
                        for (k in i + 1 until j) out[k] = ' '
                        out[j] = '\''
                        i = j + 1
                    } else {
                        out[i] = c
                        i++
                    }
                }
                else -> {
                    out[i] = c
                    i++
                }
            }
        }
        return String(out)
    }

    private fun copyStringBody(text: String, out: CharArray, from: Int, to: Int, kotlin: Boolean): Int {
        var j = from
        while (j < to) {
            val c = text[j]
            if (kotlin && c == '$' && j + 1 < to) {
                if (text[j + 1] == '{') {
                    var depth = 1
                    var k = j + 2
                    while (k < to && depth > 0) {
                        if (text[k] == '{') depth++ else if (text[k] == '}') depth--
                        k++
                    }
                    for (m in j until k) out[m] = text[m]
                    j = k
                    continue
                }
                if (text[j + 1].isLetter() || text[j + 1] == '_') {
                    var k = j + 1
                    while (k < to && (text[k].isLetterOrDigit() || text[k] == '_')) k++
                    for (m in j until k) out[m] = text[m]
                    j = k
                    continue
                }
            }
            out[j] = if (c == '\n' || c == '\r') c else ' '
            j++
        }
        return to
    }

    /** An unmatched or mismatched bracket. [line] is where the problem is (1-based). */
    data class BracketProblem(val line: Int, val message: String)

    /** Checks `() [] {}` in already masked code. Returns the first problems found (at most [max]). */
    fun bracketProblems(masked: String, max: Int = 3): List<BracketProblem> {
        val problems = ArrayList<BracketProblem>()
        val stack = ArrayList<Pair<Char, Int>>()
        var line = 1
        for (ch in masked) {
            when (ch) {
                '\n' -> line++
                '(', '[', '{' -> stack.add(ch to line)
                ')', ']', '}' -> {
                    val expected = when (ch) { ')' -> '('; ']' -> '['; else -> '{' }
                    val top = stack.lastOrNull()
                    if (top == null) {
                        problems += BracketProblem(line, "Unexpected '$ch' without a matching '$expected'.")
                        if (problems.size >= max) return problems
                    } else if (top.first != expected) {
                        problems += BracketProblem(line, "'$ch' does not match the '${top.first}' opened on line ${top.second}.")
                        if (problems.size >= max) return problems
                        stack.removeAt(stack.size - 1)
                    } else {
                        stack.removeAt(stack.size - 1)
                    }
                }
            }
        }
        for ((open, openLine) in stack.asReversed()) {
            if (problems.size >= max) break
            val close = when (open) { '(' -> ')'; '[' -> ']'; else -> '}' }
            problems += BracketProblem(openLine, "'$open' opened here is never closed (missing '$close').")
        }
        return problems
    }

    /** Whole-word occurrences of [word] in [masked]. */
    fun wordCount(masked: String, word: String): Int = wordRegex(word).findAll(masked).count()

    private val wordCache = HashMap<String, Regex>()
    fun wordRegex(word: String): Regex = synchronized(wordCache) {
        wordCache.getOrPut(word) { Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(word) + "(?![\\p{L}\\p{N}_])") }
    }
}
