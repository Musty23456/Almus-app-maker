package com.almus.appcreatorpro.editor

/**
 * Text plus selection, independent of Compose so all editor logic is unit-testable.
 * If [selStart] == [selEnd] the selection is just a caret.
 */
data class EditorValue(
    val text: String,
    val selStart: Int = 0,
    val selEnd: Int = selStart,
) {
    val selMin: Int get() = minOf(selStart, selEnd).coerceIn(0, text.length)
    val selMax: Int get() = maxOf(selStart, selEnd).coerceIn(0, text.length)
    val isCollapsed: Boolean get() = selMin == selMax
    val cursor: Int get() = selEnd.coerceIn(0, text.length)
    val selectedText: String get() = text.substring(selMin, selMax)

    /** Same value with the selection clamped to the text bounds. */
    fun coerced(): EditorValue =
        copy(selStart = selStart.coerceIn(0, text.length), selEnd = selEnd.coerceIn(0, text.length))

    /** Replaces the selection (or inserts at the caret) and puts the caret after the inserted text. */
    fun replaceSelection(insert: String): EditorValue {
        val newText = text.substring(0, selMin) + insert + text.substring(selMax)
        val caret = selMin + insert.length
        return EditorValue(newText, caret, caret)
    }
}
