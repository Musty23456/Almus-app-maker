package com.almus.appcreatorpro.debug

import java.security.MessageDigest

/**
 * A change to whole lines of a file: lines [startLine, endLineExclusive) (1-based) are replaced by [newLines].
 * Insert = start == end. Delete = empty [newLines]. Line-based edits keep the before/after preview simple and make
 * overlap detection exact.
 */
data class CodeEdit(val startLine: Int, val endLineExclusive: Int, val newLines: List<String>) {
    val removedCount: Int get() = endLineExclusive - startLine

    companion object {
        fun insert(beforeLine: Int, lines: List<String>) = CodeEdit(beforeLine, beforeLine, lines)
        fun replace(line: Int, newText: String) = CodeEdit(line, line + 1, listOf(newText))
        fun delete(line: Int) = CodeEdit(line, line + 1, emptyList())
    }
}

enum class FixOrigin(val label: String) { RULE("Rule-based"), AI("AI suggestion") }

/**
 * A suggested change to ONE file. [baseHash] is the hash of the file text the edits were written against: applying
 * to a file that has changed since is refused, so a suggestion can never silently damage newer edits.
 */
data class FixProposal(
    val id: String,
    val issueId: String?,
    val title: String,
    val explanation: String,
    val path: String,
    val edits: List<CodeEdit>,
    val origin: FixOrigin,
    val confidence: Confidence,
    val baseHash: String,
)

object TextEdits {
    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** Splits keeping a possible trailing empty element (text that ends with a line break). */
    fun lines(text: String): List<String> = text.split("\n")

    fun usesCrlf(text: String): Boolean = text.contains("\r\n")

    /** Applies the edits to [text]. Edits must not overlap and must lie inside the file. */
    fun apply(text: String, edits: List<CodeEdit>): Result {
        if (edits.isEmpty()) return Result.Invalid("There is nothing to change.")
        val lines = lines(text)
        val crlf = usesCrlf(text)
        val sorted = edits.sortedWith(compareBy({ it.startLine }, { it.endLineExclusive }))
        for (e in sorted) {
            if (e.startLine < 1 || e.endLineExclusive < e.startLine || e.endLineExclusive > lines.size + 1 || e.startLine > lines.size + 1) {
                return Result.Invalid("The change refers to lines that do not exist (${e.startLine}-${e.endLineExclusive - 1}); the file has ${lines.size} lines.")
            }
        }
        for (k in 1 until sorted.size) {
            if (sorted[k - 1].endLineExclusive > sorted[k].startLine) return Result.Invalid("Two changes overlap.")
        }
        val out = ArrayList<String>(lines.size + 8)
        var cursor = 1
        for (e in sorted) {
            for (n in cursor until e.startLine) out += lines[n - 1]
            for (nl in e.newLines) out += if (crlf && !nl.endsWith("\r")) "$nl\r" else nl
            cursor = e.endLineExclusive
        }
        for (n in cursor..lines.size) out += lines[n - 1]
        return Result.Ok(out.joinToString("\n"))
    }
}

enum class DiffKind { CONTEXT, REMOVED, ADDED }

data class DiffLine(val kind: DiffKind, val oldLine: Int?, val newLine: Int?, val text: String)

/** What the user sees before accepting a fix: the changed region before and after, and a line diff. */
data class FixPreview(
    val path: String,
    val beforeText: String,
    val afterText: String,
    val diff: List<DiffLine>,
    val firstChangedLine: Int,
    val addedLines: Int,
    val removedLines: Int,
)

object FixPreviewer {
    /** [context] unchanged lines are shown around each change. Returns null when the edits cannot be applied. */
    fun preview(original: String, proposal: FixProposal, context: Int = 2): FixPreview? {
        val applied = TextEdits.apply(original, proposal.edits) as? TextEdits.Result.Ok ?: return null
        val oldLines = TextEdits.lines(original).map { it.trimEnd('\r') }
        val sorted = proposal.edits.sortedBy { it.startLine }
        val diff = ArrayList<DiffLine>()
        val before = ArrayList<String>()
        val after = ArrayList<String>()
        var added = 0
        var removed = 0
        var newOffset = 0 // new line number = old line number + newOffset
        var lastShownOld = 0
        for (e in sorted) {
            val ctxStart = maxOf(lastShownOld + 1, e.startLine - context)
            for (n in ctxStart until e.startLine) {
                if (n in 1..oldLines.size) {
                    diff += DiffLine(DiffKind.CONTEXT, n, n + newOffset, oldLines[n - 1])
                    before += oldLines[n - 1]; after += oldLines[n - 1]
                }
            }
            for (n in e.startLine until e.endLineExclusive) {
                if (n in 1..oldLines.size) {
                    diff += DiffLine(DiffKind.REMOVED, n, null, oldLines[n - 1])
                    before += oldLines[n - 1]
                    removed++
                }
            }
            var nn = e.startLine + newOffset
            for (nl in e.newLines) {
                diff += DiffLine(DiffKind.ADDED, null, nn, nl.trimEnd('\r'))
                after += nl.trimEnd('\r')
                nn++
                added++
            }
            newOffset += e.newLines.size - e.removedCount
            lastShownOld = e.endLineExclusive - 1
            val ctxEnd = minOf(oldLines.size, e.endLineExclusive - 1 + context)
            // Trailing context is added lazily by the next edit's leading context, or after the loop.
            if (e === sorted.last()) {
                for (n in e.endLineExclusive..ctxEnd) {
                    if (n in 1..oldLines.size) {
                        diff += DiffLine(DiffKind.CONTEXT, n, n + newOffset, oldLines[n - 1])
                        before += oldLines[n - 1]; after += oldLines[n - 1]
                    }
                }
            }
        }
        return FixPreview(
            path = proposal.path,
            beforeText = before.joinToString("\n"),
            afterText = after.joinToString("\n"),
            diff = diff,
            firstChangedLine = sorted.first().startLine,
            addedLines = added,
            removedLines = removed,
        )
    }
}
