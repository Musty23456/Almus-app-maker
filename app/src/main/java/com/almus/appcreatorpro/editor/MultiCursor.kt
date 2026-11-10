package com.almus.appcreatorpro.editor

/** One caret or selection. [anchor] is where the selection started, [head] is where the caret is. */
data class Caret(val anchor: Int, val head: Int = anchor) {
    val start: Int get() = minOf(anchor, head)
    val end: Int get() = maxOf(anchor, head)
    val isEmpty: Boolean get() = anchor == head
}

data class MultiValue(val text: String, val carets: List<Caret>)

/**
 * Phase 11: several carets editing at once. The functions are pure and take/return a [MultiValue]; the editor UI is
 * responsible for turning touches (long-press "add cursor") and keys (Ctrl+D) into calls and for drawing the carets.
 * Carets are always kept sorted, inside the text and non-overlapping (overlapping ones are merged).
 */
object MultiCursor {
    const val MAX_CARETS = 500

    fun normalize(carets: List<Caret>, textLength: Int): List<Caret> {
        val clamped = carets.map { Caret(it.anchor.coerceIn(0, textLength), it.head.coerceIn(0, textLength)) }
            .sortedWith(compareBy({ it.start }, { it.end }))
        val out = ArrayList<Caret>()
        for (c in clamped) {
            val last = out.lastOrNull()
            if (last != null && (c.start < last.end || (c.start == last.start && c.end == last.end) || (c.isEmpty && last.isEmpty && c.start == last.start))) {
                // Overlap: keep one selection covering both.
                if (c.end > last.end) out[out.size - 1] = Caret(last.start, c.end)
            } else {
                out.add(c)
            }
            if (out.size >= MAX_CARETS) break
        }
        return out
    }

    fun single(value: EditorValue): MultiValue = MultiValue(value.text, listOf(Caret(value.selStart, value.selEnd)))

    /** The primary caret (the last one) as an ordinary value, to hand back to the single-cursor editor. */
    fun toEditorValue(v: MultiValue): EditorValue {
        val c = v.carets.lastOrNull() ?: Caret(0)
        return EditorValue(v.text, c.anchor, c.head).coerced()
    }

    /** Replaces every selection (or inserts at every caret) with [s]. */
    fun insert(v: MultiValue, s: String): MultiValue {
        val carets = normalize(v.carets, v.text.length)
        val sb = StringBuilder()
        val out = ArrayList<Caret>()
        var last = 0
        for (c in carets) {
            sb.append(v.text, last, c.start)
            sb.append(s)
            out.add(Caret(sb.length))
            last = c.end
        }
        sb.append(v.text, last, v.text.length)
        return MultiValue(sb.toString(), out)
    }

    fun backspace(v: MultiValue): MultiValue = deleteWith(v, backwards = true)

    fun deleteForward(v: MultiValue): MultiValue = deleteWith(v, backwards = false)

    private fun deleteWith(v: MultiValue, backwards: Boolean): MultiValue {
        val carets = normalize(v.carets, v.text.length)
        val sb = StringBuilder()
        val out = ArrayList<Caret>()
        var last = 0
        for (c in carets) {
            var from = c.start
            var to = c.end
            if (c.isEmpty) {
                if (backwards && from > last) {
                    from -= 1
                    if (from > last && v.text[from].isLowSurrogate() && v.text[from - 1].isHighSurrogate()) from -= 1
                } else if (!backwards && to < v.text.length) {
                    to += 1
                    if (to < v.text.length && v.text[to - 1].isHighSurrogate() && v.text[to].isLowSurrogate()) to += 1
                }
            }
            sb.append(v.text, last, from)
            out.add(Caret(sb.length))
            last = maxOf(last, to)
        }
        sb.append(v.text, last, v.text.length)
        return MultiValue(sb.toString(), normalize(out, sb.length))
    }

    /** Adds a caret on the line below the last caret, in the same column (or the end of a shorter line). */
    fun addCaretBelow(v: MultiValue): MultiValue = addVertical(v, +1)

    fun addCaretAbove(v: MultiValue): MultiValue = addVertical(v, -1)

    private fun addVertical(v: MultiValue, direction: Int): MultiValue {
        val carets = normalize(v.carets, v.text.length)
        val base = (if (direction > 0) carets.lastOrNull() else carets.firstOrNull()) ?: return v
        val index = LineIndex(v.text)
        val line = index.lineOf(base.head)
        val target = line + direction
        if (target < 0 || target >= index.lineCount) return v
        val offset = index.offsetOf(target, index.columnOf(base.head))
        return MultiValue(v.text, normalize(carets + Caret(offset), v.text.length))
    }

    /** Like Ctrl+D: first selects the word at the last caret, then adds the next matching occurrence each time. */
    fun selectNextOccurrence(v: MultiValue): MultiValue {
        val carets = normalize(v.carets, v.text.length)
        val last = carets.lastOrNull() ?: return v
        if (last.isEmpty) {
            val word = Navigation.wordRangeAt(v.text, last.head) ?: return v
            return MultiValue(v.text, normalize(carets.dropLast(1) + Caret(word.first, word.last + 1), v.text.length))
        }
        val needle = v.text.substring(last.start, last.end)
        if (needle.isEmpty()) return v
        val taken = carets.map { it.start }.toSet()
        var from = last.end
        var wrapped = false
        while (true) {
            val found = v.text.indexOf(needle, from)
            if (found < 0) {
                if (wrapped) return v
                wrapped = true
                from = 0
                continue
            }
            if (found !in taken) {
                return MultiValue(v.text, normalize(carets + Caret(found, found + needle.length), v.text.length))
            }
            from = found + 1
            if (from > v.text.length) return v
        }
    }

    /** Selects every occurrence of the selected text (or of the word at the caret). */
    fun selectAllOccurrences(v: MultiValue): MultiValue {
        val carets = normalize(v.carets, v.text.length)
        val last = carets.lastOrNull() ?: return v
        val needle = if (last.isEmpty) {
            Navigation.wordAt(v.text, last.head) ?: return v
        } else {
            v.text.substring(last.start, last.end)
        }
        if (needle.isEmpty()) return v
        val found = ArrayList<Caret>()
        var from = 0
        while (found.size < MAX_CARETS) {
            val i = v.text.indexOf(needle, from)
            if (i < 0) break
            found.add(Caret(i, i + needle.length))
            from = i + needle.length
        }
        return if (found.isEmpty()) v else MultiValue(v.text, found)
    }
}
