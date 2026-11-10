package com.almus.appcreatorpro.editor

/**
 * Phase 11: whole-line commands (duplicate, move, delete, toggle comment). They work on the lines touched by the selection.
 * The existing [CodeEditing] keeps typing behaviour (auto indent, brackets, indent/outdent); this adds the shortcuts
 * people expect from a desktop editor. All functions are pure.
 */
object LineCommands {
    private fun touched(v: EditorValue, index: LineIndex): IntRange {
        val first = index.lineOf(v.selMin)
        var last = index.lineOf(v.selMax)
        // A selection that ends exactly at the start of a line does not include that line.
        if (!v.isCollapsed && last > first && index.lineStart(last) == v.selMax) last--
        return first..last
    }

    fun duplicateLines(value: EditorValue): EditorValue {
        val v = value.coerced()
        val index = LineIndex(v.text)
        val r = touched(v, index)
        val start = index.lineStart(r.first)
        val end = index.lineEnd(r.last)
        val block = v.text.substring(start, end)
        val newText = v.text.substring(0, end) + "\n" + block + v.text.substring(end)
        val shift = block.length + 1
        return EditorValue(newText, v.selStart + shift, v.selEnd + shift)
    }

    fun deleteLines(value: EditorValue): EditorValue {
        val v = value.coerced()
        val index = LineIndex(v.text)
        val r = touched(v, index)
        var start = index.lineStart(r.first)
        var end = index.lineEnd(r.last)
        if (end < v.text.length) end++ else if (start > 0) start--
        val newText = v.text.substring(0, start) + v.text.substring(end)
        val caret = start.coerceIn(0, newText.length)
        return EditorValue(newText, caret, caret)
    }

    /** Moves the touched lines up or down by one line; the selection moves with them. Returns the value unchanged at the edges. */
    fun moveLines(value: EditorValue, up: Boolean): EditorValue {
        val v = value.coerced()
        val index = LineIndex(v.text)
        val r = touched(v, index)
        if (up && r.first == 0) return v
        if (!up && r.last >= index.lineCount - 1) return v
        val blockStart = index.lineStart(r.first)
        val blockEnd = index.lineEnd(r.last)
        val block = v.text.substring(blockStart, blockEnd)
        return if (up) {
            val prevStart = index.lineStart(r.first - 1)
            val prev = v.text.substring(prevStart, blockStart - 1)
            val newText = v.text.substring(0, prevStart) + block + "\n" + prev + v.text.substring(blockEnd)
            val shift = -(prev.length + 1)
            EditorValue(newText, v.selStart + shift, v.selEnd + shift)
        } else {
            val nextEnd = index.lineEnd(r.last + 1)
            val next = v.text.substring(blockEnd + 1, nextEnd)
            val newText = v.text.substring(0, blockStart) + next + "\n" + block + v.text.substring(nextEnd)
            val shift = next.length + 1
            EditorValue(newText, v.selStart + shift, v.selEnd + shift)
        }
    }

    private class CommentStyle(val open: String, val close: String)

    private fun styleFor(language: Language): CommentStyle? = when {
        language.lineComment != null -> CommentStyle(language.lineComment, "")
        language.family == SyntaxFamily.MARKUP || language == Language.MARKDOWN -> CommentStyle("<!--", "-->")
        language == Language.CSS -> CommentStyle("/*", "*/")
        else -> null
    }

    /** True when the language has a comment syntax this command knows. JSON and plain text have none. */
    fun canToggleComment(language: Language): Boolean = styleFor(language) != null

    /**
     * Comments the touched lines, or uncomments them when every non-blank one is already commented. The marker goes at the
     * smallest indentation of the block so the code keeps its shape. Blank lines are left alone.
     */
    fun toggleComment(value: EditorValue, language: Language): EditorValue {
        val style = styleFor(language) ?: return value
        val v = value.coerced()
        val index = LineIndex(v.text)
        val r = touched(v, index)
        val lines = r.map { index.lineText(it) }
        val body = lines.filter { it.isNotBlank() }
        if (body.isEmpty()) return v
        fun isCommented(l: String): Boolean {
            val t = l.trim()
            return t.startsWith(style.open) && (style.close.isEmpty() || t.endsWith(style.close))
        }
        val uncomment = body.all { isCommented(it) }
        val minIndent = body.minOf { it.length - it.trimStart().length }
        val rebuilt = lines.map { l ->
            when {
                l.isBlank() -> l
                uncomment -> {
                    val indent = l.length - l.trimStart().length
                    var rest = l.substring(indent).removePrefix(style.open)
                    if (rest.startsWith(" ")) rest = rest.substring(1)
                    if (style.close.isNotEmpty()) {
                        rest = rest.trimEnd().removeSuffix(style.close)
                        if (rest.endsWith(" ")) rest = rest.substring(0, rest.length - 1)
                    }
                    l.substring(0, indent) + rest
                }
                else -> {
                    val head = l.substring(0, minIndent)
                    val tail = l.substring(minIndent)
                    if (style.close.isEmpty()) head + style.open + " " + tail else head + style.open + " " + tail + " " + style.close
                }
            }
        }
        val start = index.lineStart(r.first)
        val end = index.lineEnd(r.last)
        val replacement = rebuilt.joinToString("\n")
        val newText = v.text.substring(0, start) + replacement + v.text.substring(end)
        val delta = replacement.length - (end - start)
        return if (v.isCollapsed) {
            val caret = (v.cursor + (rebuilt[0].length - lines[0].length)).coerceIn(start, start + replacement.length)
            EditorValue(newText, caret, caret)
        } else {
            EditorValue(newText, start, end + delta)
        }
    }
}
