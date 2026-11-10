package com.almus.appcreatorpro.editor

/** A target position typed by the user: 1-based line and column, as shown in the editor. */
data class LineTarget(val line: Int, val column: Int = 1)

/** Phase 11: "go to line", "go to definition" and "find usages". */
object Navigation {
    private val LINE_INPUT = Regex("^(\\d{1,9})(?:\\s*[:,.]\\s*(\\d{1,9}))?$")

    /** "12", "12:5", "12,5" or "12.5". Zero or non-numbers give null; a line past the end becomes the last line. */
    fun parseLineTarget(input: String, lineCount: Int): LineTarget? {
        val m = LINE_INPUT.find(input.trim()) ?: return null
        val line = m.groupValues[1].toLongOrNull() ?: return null
        if (line < 1) return null
        val column = m.groupValues[2].toLongOrNull() ?: 1L
        return LineTarget(
            line = minOf(line, maxOf(1, lineCount).toLong()).toInt(),
            column = maxOf(1L, minOf(column, 1_000_000L)).toInt(),
        )
    }

    fun offsetOf(text: String, target: LineTarget): Int = LineIndex(text).offsetOf(target.line - 1, target.column - 1)

    /** 1-based line and column of an offset. */
    fun targetOf(text: String, offset: Int): LineTarget {
        val index = LineIndex(text)
        return LineTarget(index.lineOf(offset) + 1, index.columnOf(offset) + 1)
    }

    /** The identifier around [offset] (or ending at it), as an offset range, or null when there is none. */
    fun wordRangeAt(text: String, offset: Int): IntRange? {
        val o = offset.coerceIn(0, text.length)
        fun isWord(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        var start = o
        var end = o
        while (start > 0 && isWord(text[start - 1])) start--
        while (end < text.length && isWord(text[end])) end++
        return if (end > start) start until end else null
    }

    fun wordAt(text: String, offset: Int): String? = wordRangeAt(text, offset)?.let { text.substring(it.first, it.last + 1) }
}

data class DefinitionLocation(val path: String, val line: Int, val column: Int, val symbol: Symbol)

/**
 * Where is this name declared? Looks through the outline of the current file first and then the other files, so a
 * name declared in several places returns several candidates (the current file's first). It matches by NAME only: it
 * cannot tell which of two same-named declarations a call refers to, and finds nothing for library code.
 */
object DefinitionFinder {
    private const val MAX_RESULTS = 50

    fun find(name: String, currentPath: String?, files: Map<String, String>): List<DefinitionLocation> {
        if (name.isBlank()) return emptyList()
        val ordered = files.keys.sortedWith(compareBy<String>({ if (it == currentPath) 0 else 1 }, { it }))
        val out = ArrayList<DefinitionLocation>()
        for (path in ordered) {
            val text = files[path] ?: continue
            if (!text.contains(name)) continue
            for (s in OutlineEngine.outline(text, Language.fromFileName(path))) {
                if (s.name == name || s.name.endsWith("#$name")) out += DefinitionLocation(path, s.line, s.column, s)
                if (out.size >= MAX_RESULTS) return out
            }
        }
        return out
    }

    /** All symbol names of the given files, for completion. */
    fun symbolNames(files: Map<String, String>, limit: Int = 2000): Set<String> {
        val names = LinkedHashSet<String>()
        for ((path, text) in files) {
            for (s in OutlineEngine.outline(text, Language.fromFileName(path))) {
                if (s.kind == SymbolKind.SECTION || s.kind == SymbolKind.KEY || s.kind == SymbolKind.ELEMENT) continue
                names.add(s.name)
                if (names.size >= limit) return names
            }
        }
        return names
    }
}
