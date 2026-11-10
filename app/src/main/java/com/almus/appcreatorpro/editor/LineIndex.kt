package com.almus.appcreatorpro.editor

/**
 * Phase 11: maps between character offsets and 0-based lines / columns. Plain Kotlin, so it is unit-tested on a JVM.
 * A text always has at least one line (an empty text has one empty line). A line ends at '\n'; a '\r' stays in the line.
 */
class LineIndex(val text: String) {
    private val starts: IntArray

    init {
        val list = ArrayList<Int>()
        list.add(0)
        for (i in text.indices) {
            if (text[i] == '\n') list.add(i + 1)
        }
        starts = list.toIntArray()
    }

    val lineCount: Int get() = starts.size

    fun lineStart(line: Int): Int = starts[line.coerceIn(0, starts.size - 1)]

    /** Offset of the end of the line (before the '\n'). */
    fun lineEnd(line: Int): Int {
        val l = line.coerceIn(0, starts.size - 1)
        return if (l + 1 < starts.size) starts[l + 1] - 1 else text.length
    }

    fun lineText(line: Int): String = text.substring(lineStart(line), lineEnd(line))

    /** The line that contains [offset] (an offset equal to the text length belongs to the last line). */
    fun lineOf(offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= o) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun columnOf(offset: Int): Int = offset.coerceIn(0, text.length) - lineStart(lineOf(offset))

    /** Offset for a 0-based line and column; the column is clamped to the end of the line. */
    fun offsetOf(line: Int, column: Int): Int =
        (lineStart(line) + column.coerceAtLeast(0)).coerceAtMost(lineEnd(line))
}
