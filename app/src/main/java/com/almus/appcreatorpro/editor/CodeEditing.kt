package com.almus.appcreatorpro.editor

/** Pure text transformations used by the editor: auto-indent, indent and outdent. */
object CodeEditing {
    const val INDENT = "    "

    /**
     * Called after the user typed. If a single newline was typed, keeps the previous line's
     * indentation, adds one level after an opening bracket, and moves a directly following
     * closing bracket onto its own line. Any other edit is returned unchanged.
     */
    fun afterInput(old: EditorValue, new: EditorValue): EditorValue {
        if (new.text.length != old.text.length + 1 || !new.isCollapsed) return new
        val caret = new.selEnd
        if (caret < 1 || caret > new.text.length || new.text[caret - 1] != '\n') return new
        if (new.text.removeRange(caret - 1, caret) != old.text) return new

        val before = new.text.substring(0, caret - 1)
        val previousLine = before.substring(before.lastIndexOf('\n') + 1)
        val baseIndent = previousLine.takeWhile { it == ' ' || it == '\t' }
        val opener = previousLine.trimEnd().lastOrNull()
        val opensBlock = opener != null && opener in "{(["
        val extra = if (opensBlock) INDENT else ""
        val insertion = baseIndent + extra

        val nextChar = new.text.getOrNull(caret)
        val closesImmediately = opensBlock && nextChar != null && nextChar == matching(opener!!)

        val head = new.text.substring(0, caret)
        val tail = new.text.substring(caret)
        val newCaret = caret + insertion.length
        val text = if (closesImmediately) head + insertion + "\n" + baseIndent + tail else head + insertion + tail
        return EditorValue(text, newCaret, newCaret)
    }

    private fun matching(open: Char): Char = when (open) {
        '{' -> '}'
        '(' -> ')'
        else -> ']'
    }

    /** Tab key: a caret inserts one indent; a selection indents every line it touches. */
    fun indent(v: EditorValue): EditorValue {
        val value = v.coerced()
        if (value.isCollapsed) return value.replaceSelection(INDENT)
        return rebuild(value, touchedLines(value)) { text -> if (text.isEmpty()) text else INDENT + text }
    }

    /** Shift+Tab: removes up to one indent level from every line touched by the selection or caret. */
    fun outdent(v: EditorValue): EditorValue {
        val value = v.coerced()
        val lines = touchedLines(value)
        return rebuild(value, lines) { text ->
            when {
                text.startsWith("\t") -> text.substring(1)
                else -> text.substring(minOf(text.takeWhile { it == ' ' }.length, INDENT.length))
            }
        }
    }

    private data class LineRange(val start: Int, val end: Int)

    private fun touchedLines(v: EditorValue): List<LineRange> {
        val text = v.text
        val first = text.lastIndexOf('\n', v.selMin - 1) + 1
        // A selection that ends right after a newline does not include the next line.
        val endRef = if (v.selMax > v.selMin && text[v.selMax - 1] == '\n') v.selMax - 1 else v.selMax
        val lastEnd = text.indexOf('\n', endRef).let { if (it < 0) text.length else it }
        val result = ArrayList<LineRange>()
        var start = first
        while (start <= lastEnd) {
            val end = text.indexOf('\n', start).let { if (it < 0 || it > lastEnd) lastEnd else it }
            result += LineRange(start, end)
            start = end + 1
        }
        return result
    }

    /** Rewrites each line with [transform] and maps the selection through the changes. */
    private fun rebuild(v: EditorValue, lines: List<LineRange>, transform: (String) -> String): EditorValue {
        val text = v.text
        val sb = StringBuilder()
        var cursor = 0
        // (lineStart, delta): delta > 0 inserted at lineStart, delta < 0 removed from lineStart.
        val changes = ArrayList<Pair<Int, Int>>()
        for (line in lines) {
            sb.append(text, cursor, line.start)
            val original = text.substring(line.start, line.end)
            val changed = transform(original)
            sb.append(changed)
            val delta = changed.length - original.length
            if (delta != 0) changes += line.start to delta
            cursor = line.end
        }
        sb.append(text, cursor, text.length)

        fun map(p: Int): Int {
            var result = p
            for ((start, delta) in changes) {
                result += if (delta > 0) {
                    if (p >= start) delta else 0
                } else {
                    -minOf(-delta, maxOf(0, p - start))
                }
            }
            return result
        }
        val newStart = map(v.selStart)
        val newEnd = map(v.selEnd)
        return EditorValue(sb.toString(), newStart, newEnd).coerced()
    }
}
