package com.almus.appcreatorpro.editor

/**
 * Undo/redo for one open file. Stores full snapshots (simple and reliable), bounded by entry count
 * and total characters. Consecutive single-character typing or deleting within [coalesceMillis] is
 * merged into one undo step; a newline always starts a new step.
 */
class UndoHistory(
    private val maxEntries: Int = 200,
    private val maxChars: Int = 4_000_000,
    private val coalesceMillis: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class Kind { INSERT, DELETE, OTHER }

    private val undoStack = ArrayDeque<EditorValue>()
    private val redoStack = ArrayDeque<EditorValue>()
    private var totalChars = 0L
    private var lastKind = Kind.OTHER
    private var lastTime = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Call after every change of the text; [before] is the value before the change. */
    fun record(before: EditorValue, after: EditorValue) {
        if (before.text == after.text) return
        val delta = after.text.length - before.text.length
        val kind = when {
            delta == 1 && after.selEnd - 1 in after.text.indices && after.text[after.selEnd - 1] != '\n' -> Kind.INSERT
            delta == -1 -> Kind.DELETE
            else -> Kind.OTHER
        }
        val now = clock()
        val merge = kind != Kind.OTHER && kind == lastKind && now - lastTime <= coalesceMillis && undoStack.isNotEmpty()
        if (!merge) push(before)
        redoStack.clear()
        lastKind = kind
        lastTime = now
    }

    /** Returns the value to show after undoing, or null if there is nothing to undo. */
    fun undo(current: EditorValue): EditorValue? {
        val previous = undoStack.removeLastOrNull() ?: return null
        totalChars -= previous.text.length
        redoStack.addLast(current)
        lastKind = Kind.OTHER
        return previous
    }

    /** Returns the value to show after redoing, or null if there is nothing to redo. */
    fun redo(current: EditorValue): EditorValue? {
        val next = redoStack.removeLastOrNull() ?: return null
        push(current)
        lastKind = Kind.OTHER
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        totalChars = 0
        lastKind = Kind.OTHER
    }

    private fun push(value: EditorValue) {
        undoStack.addLast(value)
        totalChars += value.text.length
        while (undoStack.size > maxEntries || (totalChars > maxChars && undoStack.size > 1)) {
            totalChars -= undoStack.removeFirst().text.length
        }
    }
}
