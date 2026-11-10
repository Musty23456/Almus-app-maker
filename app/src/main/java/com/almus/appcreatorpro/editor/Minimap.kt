package com.almus.appcreatorpro.editor

/** One row of the minimap: the lines [firstLine]..[lastLine] summarised as [density] 0..1 (how much of the line is ink). */
data class MinimapRow(val firstLine: Int, val lastLine: Int, val density: Float, val indentFraction: Float)

/**
 * Phase 11: the data behind a code minimap. A minimap draws one thin bar per group of lines; this class only computes
 * the bars and the mapping between lines and positions, so the drawing (a small Canvas) stays trivial. On phones a
 * minimap takes scarce width, so the UI should show it on wide screens only.
 */
object MinimapModel {
    private const val FULL_LINE_CHARS = 100

    /** Summarises [text] into at most [maxRows] rows (at least one row per line when the text is short). */
    fun build(text: String, maxRows: Int): List<MinimapRow> {
        val index = LineIndex(text)
        val lines = index.lineCount
        val rows = maxRows.coerceIn(1, 2000)
        val perRow = (lines + rows - 1) / rows
        val out = ArrayList<MinimapRow>()
        var first = 0
        while (first < lines) {
            val last = minOf(lines - 1, first + perRow - 1)
            var ink = 0f
            var indent = 0f
            var counted = 0
            for (l in first..last) {
                val s = index.lineText(l).trimEnd('\r')
                val trimmed = s.trimStart()
                if (trimmed.isNotEmpty()) {
                    ink += minOf(trimmed.length, FULL_LINE_CHARS).toFloat() / FULL_LINE_CHARS
                    indent += minOf(s.length - trimmed.length, 40).toFloat() / 40f
                    counted++
                }
            }
            val n = (last - first + 1).toFloat()
            out.add(MinimapRow(first, last, ink / n, if (counted == 0) 0f else indent / counted))
            first = last + 1
        }
        return out
    }

    /** The visible part of the file as fractions (0..1) of the minimap height: start and end. */
    fun viewport(firstVisibleLine: Int, visibleLines: Int, lineCount: Int): Pair<Float, Float> {
        if (lineCount <= 0) return Pair(0f, 1f)
        val start = firstVisibleLine.coerceIn(0, lineCount - 1).toFloat() / lineCount
        val end = (firstVisibleLine + visibleLines.coerceAtLeast(1)).coerceAtMost(lineCount).toFloat() / lineCount
        return Pair(start, maxOf(start, end))
    }

    /** The line a tap at [fraction] (0 = top, 1 = bottom) of the minimap should scroll to. */
    fun lineAt(fraction: Float, lineCount: Int): Int {
        if (lineCount <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * lineCount).toInt().coerceIn(0, lineCount - 1)
    }
}
