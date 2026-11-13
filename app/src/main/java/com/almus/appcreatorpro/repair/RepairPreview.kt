package com.almus.appcreatorpro.repair

import com.almus.appcreatorpro.ai.LineDiff
import com.almus.appcreatorpro.ai.ProposedChange
import com.almus.appcreatorpro.debug.DiffKind
import com.almus.appcreatorpro.debug.DiffLine

enum class SideKind { SAME, REMOVED, ADDED, CHANGED }

/** One row of the two-column view. A missing side is null (a pure insertion has no old text, a deletion no new text). */
data class SideRow(val kind: SideKind, val oldLine: Int?, val oldText: String?, val newLine: Int?, val newText: String?)

/**
 * Everything the "look before you accept" screen shows for one file: the original text, the modified text, the
 * line-by-line differences (one list, and the same differences in two columns). `null` entries mark unchanged lines
 * that were skipped between two changes.
 */
data class RepairPreview(
    val path: String,
    val originalText: String,
    val modifiedText: String,
    val unified: List<DiffLine?>,
    val sideBySide: List<SideRow?>,
    val added: Int,
    val removed: Int,
    val hunks: Int,
)

object RepairPreviews {
    fun of(patch: RepairFilePatch, context: Int = 3): RepairPreview = of(patch.path, patch.change, context)

    fun of(path: String, change: ProposedChange, context: Int = 3): RepairPreview {
        val full = LineDiff.diff(change.before, change.after)
        val collapsed = LineDiff.collapse(full, context)
        return RepairPreview(
            path = path,
            originalText = change.before.orEmpty(),
            modifiedText = change.after,
            unified = collapsed,
            sideBySide = sideBySide(collapsed),
            added = full.count { it.kind == DiffKind.ADDED },
            removed = full.count { it.kind == DiffKind.REMOVED },
            hunks = countHunks(full),
        )
    }

    private fun countHunks(lines: List<DiffLine>): Int {
        var hunks = 0
        var inChange = false
        for (l in lines) {
            if (l.kind == DiffKind.CONTEXT) inChange = false else if (!inChange) { hunks++; inChange = true }
        }
        return hunks
    }

    /** Pairs each run of removed lines with the added lines that replace it. */
    fun sideBySide(lines: List<DiffLine?>): List<SideRow?> {
        val out = ArrayList<SideRow?>()
        val removedRun = ArrayList<DiffLine>()
        val addedRun = ArrayList<DiffLine>()
        fun flush() {
            val n = maxOf(removedRun.size, addedRun.size)
            for (i in 0 until n) {
                val r = removedRun.getOrNull(i)
                val a = addedRun.getOrNull(i)
                val kind = when {
                    r != null && a != null -> SideKind.CHANGED
                    r != null -> SideKind.REMOVED
                    else -> SideKind.ADDED
                }
                out += SideRow(kind, r?.oldLine, r?.text, a?.newLine, a?.text)
            }
            removedRun.clear()
            addedRun.clear()
        }
        for (l in lines) {
            if (l == null) { flush(); out += null; continue }
            when (l.kind) {
                DiffKind.CONTEXT -> { flush(); out += SideRow(SideKind.SAME, l.oldLine, l.text, l.newLine, l.text) }
                DiffKind.REMOVED -> { if (addedRun.isNotEmpty()) flush(); removedRun += l }
                DiffKind.ADDED -> addedRun += l
            }
        }
        flush()
        return out
    }
}
