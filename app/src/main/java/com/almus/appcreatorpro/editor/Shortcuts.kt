package com.almus.appcreatorpro.editor

enum class EditorCommand(val label: String) {
    SAVE("Save"), SAVE_ALL("Save all"), UNDO("Undo"), REDO("Redo"),
    FIND("Find"), REPLACE("Find and replace"), GO_TO_LINE("Go to line"), GO_TO_DEFINITION("Go to definition"),
    FIND_USAGES("Find usages"), OUTLINE("File outline"), PROJECT_SEARCH("Search in project"), QUICK_OPEN("Open file"),
    NEXT_TAB("Next tab"), PREVIOUS_TAB("Previous tab"), CLOSE_TAB("Close tab"),
    COMPLETE("Show suggestions"), TOGGLE_FOLD("Fold or unfold"), TOGGLE_COMMENT("Toggle comment"),
    DUPLICATE_LINE("Duplicate line"), DELETE_LINE("Delete line"), MOVE_LINE_UP("Move line up"), MOVE_LINE_DOWN("Move line down"),
    INDENT("Indent"), OUTDENT("Outdent"),
    ADD_CURSOR_BELOW("Add cursor below"), ADD_CURSOR_ABOVE("Add cursor above"),
    SELECT_NEXT_OCCURRENCE("Select next occurrence"), SELECT_ALL_OCCURRENCES("Select all occurrences"),
    TOGGLE_SPLIT("Split editor"), AI_EXPLAIN("Explain with AI"), AI_SUGGEST("Suggest code with AI"),
}

/**
 * A key combination. [key] is the key's name in upper case ("S", "F", "TAB", "ENTER", "SPACE", "F3", "/", "]", "PAGEDOWN").
 * The UI translates its key events into this class, so the bindings can be tested without Android.
 */
data class KeyChord(val key: String, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {
    /** "Ctrl+Shift+P". */
    override fun toString(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(key)
    }

    companion object {
        /** Parses "Ctrl+Shift+P" (any order, case-insensitive; "Cmd" and "Meta" count as Ctrl). Null for nonsense. */
        fun parse(text: String): KeyChord? {
            val parts = text.split('+').map { it.trim() }
            if (parts.isEmpty() || parts.any { it.isEmpty() }) return null
            var ctrl = false
            var shift = false
            var alt = false
            var key: String? = null
            for (p in parts) {
                when (p.lowercase()) {
                    "ctrl", "control", "cmd", "meta" -> ctrl = true
                    "shift" -> shift = true
                    "alt", "option" -> alt = true
                    else -> {
                        if (key != null) return null
                        key = p.uppercase()
                    }
                }
            }
            return key?.let { KeyChord(it, ctrl, shift, alt) }
        }
    }
}

/**
 * Phase 11: keyboard shortcuts for external keyboards. A binding maps one chord to one command; assigning a chord that is
 * already used reports the conflict instead of silently replacing it.
 */
class ShortcutMap private constructor(private val bindings: Map<KeyChord, EditorCommand>) {
    fun resolve(chord: KeyChord): EditorCommand? = bindings[chord]

    fun chordFor(command: EditorCommand): KeyChord? = bindings.entries.firstOrNull { it.value == command }?.key

    fun all(): Map<KeyChord, EditorCommand> = bindings

    /** The command already using [chord], if it is a different one from [command]. */
    fun conflict(chord: KeyChord, command: EditorCommand): EditorCommand? = bindings[chord]?.takeIf { it != command }

    /** Returns the new map, or null when [chord] is taken by another command. Any older chord of [command] is removed. */
    fun rebind(command: EditorCommand, chord: KeyChord): ShortcutMap? {
        if (conflict(chord, command) != null) return null
        val next = LinkedHashMap<KeyChord, EditorCommand>()
        for ((k, v) in bindings) if (v != command) next[k] = v
        next[chord] = command
        return ShortcutMap(next)
    }

    companion object {
        fun of(pairs: List<Pair<KeyChord, EditorCommand>>): ShortcutMap {
            val map = LinkedHashMap<KeyChord, EditorCommand>()
            for ((chord, command) in pairs) map[chord] = command
            return ShortcutMap(map)
        }

        fun defaults(): ShortcutMap = of(
            listOf(
                KeyChord("S", ctrl = true) to EditorCommand.SAVE,
                KeyChord("S", ctrl = true, shift = true) to EditorCommand.SAVE_ALL,
                KeyChord("Z", ctrl = true) to EditorCommand.UNDO,
                KeyChord("Z", ctrl = true, shift = true) to EditorCommand.REDO,
                KeyChord("Y", ctrl = true) to EditorCommand.REDO,
                KeyChord("F", ctrl = true) to EditorCommand.FIND,
                KeyChord("H", ctrl = true) to EditorCommand.REPLACE,
                KeyChord("G", ctrl = true) to EditorCommand.GO_TO_LINE,
                KeyChord("B", ctrl = true) to EditorCommand.GO_TO_DEFINITION,
                KeyChord("U", ctrl = true, shift = true) to EditorCommand.FIND_USAGES,
                KeyChord("O", ctrl = true, shift = true) to EditorCommand.OUTLINE,
                KeyChord("F", ctrl = true, shift = true) to EditorCommand.PROJECT_SEARCH,
                KeyChord("P", ctrl = true) to EditorCommand.QUICK_OPEN,
                KeyChord("TAB", ctrl = true) to EditorCommand.NEXT_TAB,
                KeyChord("TAB", ctrl = true, shift = true) to EditorCommand.PREVIOUS_TAB,
                KeyChord("W", ctrl = true) to EditorCommand.CLOSE_TAB,
                KeyChord("SPACE", ctrl = true) to EditorCommand.COMPLETE,
                KeyChord("[", ctrl = true, shift = true) to EditorCommand.TOGGLE_FOLD,
                KeyChord("/", ctrl = true) to EditorCommand.TOGGLE_COMMENT,
                KeyChord("D", ctrl = true, shift = true) to EditorCommand.DUPLICATE_LINE,
                KeyChord("K", ctrl = true, shift = true) to EditorCommand.DELETE_LINE,
                KeyChord("UP", alt = true) to EditorCommand.MOVE_LINE_UP,
                KeyChord("DOWN", alt = true) to EditorCommand.MOVE_LINE_DOWN,
                KeyChord("]", ctrl = true) to EditorCommand.INDENT,
                KeyChord("[", ctrl = true) to EditorCommand.OUTDENT,
                KeyChord("DOWN", ctrl = true, alt = true) to EditorCommand.ADD_CURSOR_BELOW,
                KeyChord("UP", ctrl = true, alt = true) to EditorCommand.ADD_CURSOR_ABOVE,
                KeyChord("D", ctrl = true) to EditorCommand.SELECT_NEXT_OCCURRENCE,
                KeyChord("L", ctrl = true, shift = true) to EditorCommand.SELECT_ALL_OCCURRENCES,
                KeyChord("\\", ctrl = true) to EditorCommand.TOGGLE_SPLIT,
                KeyChord("E", ctrl = true, alt = true) to EditorCommand.AI_EXPLAIN,
                KeyChord("I", ctrl = true, alt = true) to EditorCommand.AI_SUGGEST,
            ),
        )
    }
}
