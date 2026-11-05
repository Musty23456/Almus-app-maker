package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.DiffKind
import com.almus.appcreatorpro.debug.DiffLine

data class DiffStats(val added: Int, val removed: Int)

/**
 * Line diff for the before/after preview of whole-file changes. Common first and last lines are trimmed, the middle is
 * compared with a longest-common-subsequence table (bounded: very different big files fall back to "all replaced").
 */
object LineDiff {
    private const val MAX_CELLS = 4_000_000L

    fun diff(before: String?, after: String): List<DiffLine> {
        val a = split(before.orEmpty())
        val b = split(after)
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        val out = ArrayList<DiffLine>()
        for (i in 0 until start) out += DiffLine(DiffKind.CONTEXT, i + 1, i + 1, a[i])
        val midA = a.subList(start, endA)
        val midB = b.subList(start, endB)
        out += middle(midA, midB, start)
        val tailA = endA
        val tailB = endB
        for (k in 0 until (a.size - tailA)) out += DiffLine(DiffKind.CONTEXT, tailA + k + 1, tailB + k + 1, a[tailA + k])
        return out
    }

    private fun middle(a: List<String>, b: List<String>, offset: Int): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        if (a.isEmpty() || b.isEmpty() || a.size.toLong() * b.size > MAX_CELLS) {
            a.forEachIndexed { i, t -> out += DiffLine(DiffKind.REMOVED, offset + i + 1, null, t) }
            b.forEachIndexed { i, t -> out += DiffLine(DiffKind.ADDED, null, offset + i + 1, t) }
            return out
        }
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += DiffLine(DiffKind.CONTEXT, offset + i + 1, offset + j + 1, a[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out += DiffLine(DiffKind.REMOVED, offset + i + 1, null, a[i]); i++ }
                else -> { out += DiffLine(DiffKind.ADDED, null, offset + j + 1, b[j]); j++ }
            }
        }
        while (i < n) { out += DiffLine(DiffKind.REMOVED, offset + i + 1, null, a[i]); i++ }
        while (j < m) { out += DiffLine(DiffKind.ADDED, null, offset + j + 1, b[j]); j++ }
        return out
    }

    fun stats(lines: List<DiffLine>): DiffStats =
        DiffStats(lines.count { it.kind == DiffKind.ADDED }, lines.count { it.kind == DiffKind.REMOVED })

    /** Changed lines with [context] unchanged lines around them; gaps are dropped (null marks "unchanged lines skipped"). */
    fun collapse(lines: List<DiffLine>, context: Int = 3): List<DiffLine?> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { idx, l ->
            if (l.kind != DiffKind.CONTEXT) for (k in maxOf(0, idx - context)..minOf(lines.size - 1, idx + context)) keep[k] = true
        }
        val out = ArrayList<DiffLine?>()
        var skipped = false
        lines.forEachIndexed { idx, l ->
            if (keep[idx]) { out += l; skipped = false } else if (!skipped && out.isNotEmpty()) { out += null; skipped = true }
        }
        if (out.lastOrNull() == null && out.isNotEmpty()) out.removeAt(out.size - 1)
        return out
    }

    private fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.replace("\r\n", "\n").split('\n')
        return if (lines.last().isEmpty()) lines.dropLast(1) else lines
    }
}
