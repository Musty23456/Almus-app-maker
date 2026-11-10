package com.almus.appcreatorpro.editor

/**
 * A foldable block. When it is folded, the lines [startLine]+1 .. [endLine] (both 0-based, inclusive) are hidden and the
 * start line stays visible. A region always spans at least two lines.
 */
data class FoldRegion(val startLine: Int, val endLine: Int) {
    init {
        require(endLine > startLine) { "A fold region needs at least two lines" }
    }
}

/**
 * Phase 11: finds foldable blocks. Brackets (C-like languages, JSON, SQL), tag pairs (HTML, XML), indentation (Python, YAML),
 * headings and fenced code (Markdown) and multi-line block comments. Brackets inside strings and comments are ignored.
 * It does not parse the language: a block the file itself writes inconsistently (unbalanced braces) is simply not offered.
 * This class only computes regions; drawing the fold markers and hiding lines is the job of the editor UI.
 */
object FoldingEngine {
    const val MAX_CHARS = 500_000
    private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr", "param")
    private val TAG = Regex("<(/?)([A-Za-z][\\w:.-]*)([^<>]*?)(/?)>")

    fun regions(text: String, language: Language): List<FoldRegion> {
        if (text.isEmpty() || text.length > MAX_CHARS) return emptyList()
        val index = LineIndex(text)
        val raw = when {
            language == Language.PYTHON || language == Language.YAML -> byIndentation(index)
            language == Language.MARKDOWN -> markdown(index)
            language.family == SyntaxFamily.MARKUP -> tags(text, language, index)
            language.family == SyntaxFamily.C_LIKE -> brackets(text, language, index)
            else -> emptyList()
        }
        // Keep the largest region per start line, ordered by start line.
        val best = HashMap<Int, FoldRegion>()
        for (r in raw) {
            val old = best[r.startLine]
            if (old == null || r.endLine > old.endLine) best[r.startLine] = r
        }
        return best.values.sortedBy { it.startLine }
    }

    private fun brackets(text: String, language: Language, index: LineIndex): List<FoldRegion> {
        val skip = SyntaxHighlighter.tokenize(text, language).filter { it.type == TokenType.COMMENT || it.type == TokenType.STRING }
        val out = ArrayList<FoldRegion>()
        for (t in skip) {
            if (t.type == TokenType.COMMENT) addIfMultiLine(out, index.lineOf(t.start), index.lineOf((t.end - 1).coerceAtLeast(t.start)))
        }
        val stackChars = ArrayList<Char>()
        val stackLines = ArrayList<Int>()
        var next = 0
        var i = 0
        val n = text.length
        while (i < n) {
            while (next < skip.size && skip[next].end <= i) next++
            if (next < skip.size && i >= skip[next].start) {
                i = skip[next].end
                continue
            }
            val c = text[i]
            if (c == '{' || c == '(' || c == '[') {
                stackChars.add(c)
                stackLines.add(index.lineOf(i))
            } else if (c == '}' || c == ')' || c == ']') {
                val want = when (c) { '}' -> '{'; ')' -> '('; else -> '[' }
                val top = stackChars.size - 1
                if (top >= 0 && stackChars[top] == want) {
                    addIfMultiLine(out, stackLines[top], index.lineOf(i))
                    stackChars.removeAt(top)
                    stackLines.removeAt(top)
                }
            }
            i++
        }
        return out
    }

    private fun addIfMultiLine(out: MutableList<FoldRegion>, start: Int, end: Int) {
        if (end > start) out += FoldRegion(start, end)
    }

    private fun tags(text: String, language: Language, index: LineIndex): List<FoldRegion> {
        val comments = SyntaxHighlighter.tokenize(text, language).filter { it.type == TokenType.COMMENT }
        val out = ArrayList<FoldRegion>()
        for (t in comments) addIfMultiLine(out, index.lineOf(t.start), index.lineOf((t.end - 1).coerceAtLeast(t.start)))
        val names = ArrayList<String>()
        val lines = ArrayList<Int>()
        for (m in TAG.findAll(text)) {
            val start = m.range.first
            if (comments.any { start >= it.start && start < it.end }) continue
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val selfClosing = m.groupValues[4] == "/"
            if (closing) {
                val pos = names.lastIndexOf(name)
                if (pos >= 0) {
                    addIfMultiLine(out, lines[pos], index.lineOf(start))
                    while (names.size > pos) {
                        names.removeAt(names.size - 1)
                        lines.removeAt(lines.size - 1)
                    }
                }
            } else if (!selfClosing && !(language == Language.HTML && name in VOID_TAGS)) {
                names.add(name)
                lines.add(index.lineOf(start))
            }
        }
        return out
    }

    private fun indentOf(line: String): Int {
        var w = 0
        for (ch in line) {
            if (ch == ' ') w++ else if (ch == '\t') w += 4 else break
        }
        return w
    }

    private fun byIndentation(index: LineIndex): List<FoldRegion> {
        val out = ArrayList<FoldRegion>()
        val stackLine = ArrayList<Int>()
        val stackIndent = ArrayList<Int>()
        var lastNonBlank = -1
        for (line in 0 until index.lineCount) {
            val raw = index.lineText(line).trimEnd('\r')
            if (raw.isBlank()) continue
            val d = indentOf(raw)
            while (stackIndent.isNotEmpty() && stackIndent[stackIndent.size - 1] >= d) {
                val start = stackLine.removeAt(stackLine.size - 1)
                stackIndent.removeAt(stackIndent.size - 1)
                addIfMultiLine(out, start, lastNonBlank)
            }
            stackLine.add(line)
            stackIndent.add(d)
            lastNonBlank = line
        }
        while (stackLine.isNotEmpty()) {
            val start = stackLine.removeAt(stackLine.size - 1)
            stackIndent.removeAt(stackIndent.size - 1)
            addIfMultiLine(out, start, lastNonBlank)
        }
        return out
    }

    private fun markdown(index: LineIndex): List<FoldRegion> {
        val out = ArrayList<FoldRegion>()
        val stackLine = ArrayList<Int>()
        val stackLevel = ArrayList<Int>()
        var fenceStart = -1
        var lastNonBlank = -1
        for (line in 0 until index.lineCount) {
            val raw = index.lineText(line).trimEnd('\r')
            val trimmed = raw.trimStart()
            val isFence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
            if (isFence) {
                if (fenceStart < 0) fenceStart = line else {
                    addIfMultiLine(out, fenceStart, line)
                    fenceStart = -1
                }
                if (raw.isNotBlank()) lastNonBlank = line
                continue
            }
            if (raw.isBlank()) continue
            if (fenceStart < 0 && raw.startsWith("#")) {
                var level = 0
                while (level < raw.length && raw[level] == '#') level++
                if (level <= 6 && level < raw.length && raw[level] == ' ') {
                    while (stackLevel.isNotEmpty() && stackLevel[stackLevel.size - 1] >= level) {
                        val start = stackLine.removeAt(stackLine.size - 1)
                        stackLevel.removeAt(stackLevel.size - 1)
                        addIfMultiLine(out, start, lastNonBlank)
                    }
                    stackLine.add(line)
                    stackLevel.add(level)
                }
            }
            lastNonBlank = line
        }
        while (stackLine.isNotEmpty()) {
            val start = stackLine.removeAt(stackLine.size - 1)
            stackLevel.removeAt(stackLevel.size - 1)
            addIfMultiLine(out, start, lastNonBlank)
        }
        return out
    }

    /** Lines that stay visible when the regions starting at [folded] lines are folded, in order. */
    fun visibleLines(lineCount: Int, regions: List<FoldRegion>, folded: Set<Int>): List<Int> {
        if (folded.isEmpty()) return (0 until lineCount).toList()
        val hidden = BooleanArray(lineCount)
        for (r in regions) {
            if (r.startLine in folded) {
                for (l in (r.startLine + 1)..minOf(r.endLine, lineCount - 1)) hidden[l] = true
            }
        }
        val out = ArrayList<Int>()
        for (l in 0 until lineCount) if (!hidden[l]) out.add(l)
        return out
    }

    fun toggle(folded: Set<Int>, startLine: Int): Set<Int> = if (startLine in folded) folded - startLine else folded + startLine

    /** The region that starts on [line], if any. */
    fun regionStartingAt(regions: List<FoldRegion>, line: Int): FoldRegion? = regions.firstOrNull { it.startLine == line }

    /** Drops fold marks whose region no longer exists, e.g. after the text was edited. */
    fun retain(folded: Set<Int>, regions: List<FoldRegion>): Set<Int> {
        val starts = regions.map { it.startLine }.toSet()
        return folded.filter { it in starts }.toSet()
    }
}
