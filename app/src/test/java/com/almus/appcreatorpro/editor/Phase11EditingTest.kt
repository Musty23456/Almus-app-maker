package com.almus.appcreatorpro.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11NavigationTest {
    @Test
    fun line_targets_accept_line_and_optional_column() {
        assertEquals(LineTarget(12, 1), Navigation.parseLineTarget("12", 100))
        assertEquals(LineTarget(12, 5), Navigation.parseLineTarget("12:5", 100))
        assertEquals(LineTarget(3, 7), Navigation.parseLineTarget(" 3 , 7 ", 100))
        assertEquals(LineTarget(4, 2), Navigation.parseLineTarget("4.2", 100))
    }

    @Test
    fun bad_line_targets_are_rejected_and_big_ones_clamped() {
        assertNull(Navigation.parseLineTarget("", 10))
        assertNull(Navigation.parseLineTarget("abc", 10))
        assertNull(Navigation.parseLineTarget("0", 10))
        assertNull(Navigation.parseLineTarget("-4", 10))
        assertNull(Navigation.parseLineTarget("1:2:3", 10))
        assertEquals(LineTarget(10, 1), Navigation.parseLineTarget("999", 10))
        assertEquals(LineTarget(5, 1), Navigation.parseLineTarget("5:0", 10))
    }

    @Test
    fun offsets_and_targets_convert_both_ways() {
        val text = "ab\ncde"
        assertEquals(4, Navigation.offsetOf(text, LineTarget(2, 2)))
        assertEquals(LineTarget(2, 2), Navigation.targetOf(text, 4))
        assertEquals(6, Navigation.offsetOf(text, LineTarget(2, 99)))
        assertEquals(LineTarget(1, 1), Navigation.targetOf("", 0))
    }

    @Test
    fun word_at_finds_the_identifier() {
        assertEquals("barBaz", Navigation.wordAt("foo.barBaz(1)", 6))
        assertEquals("abc", Navigation.wordAt("abc", 3))
        assertNull(Navigation.wordAt("a + b", 2))
    }

    @Test
    fun line_index_basics() {
        val index = LineIndex("ab\ncd\n")
        assertEquals(3, index.lineCount)
        assertEquals(1, index.lineOf(3))
        assertEquals(2, index.lineOf(6))
        assertEquals("cd", index.lineText(1))
        assertEquals(0, LineIndex("").lineOf(0))
        assertEquals(1, LineIndex("").lineCount)
    }
}

class Phase11LineCommandsTest {
    @Test
    fun duplicate_a_line_moves_the_caret_to_the_copy() {
        val v = LineCommands.duplicateLines(EditorValue("a\nb\nc", 2))
        assertEquals("a\nb\nb\nc", v.text)
        assertEquals(4, v.cursor)
    }

    @Test
    fun duplicate_a_selection_of_lines() {
        val v = LineCommands.duplicateLines(EditorValue("a\nb\nc", 0, 3))
        assertEquals("a\nb\na\nb\nc", v.text)
        assertEquals(4, v.selMin)
        assertEquals(7, v.selMax)
    }

    @Test
    fun a_selection_ending_at_a_line_start_does_not_include_that_line() {
        assertEquals("a\na\nb", LineCommands.duplicateLines(EditorValue("a\nb", 0, 2)).text)
    }

    @Test
    fun delete_lines() {
        assertEquals("a\nc", LineCommands.deleteLines(EditorValue("a\nb\nc", 2)).text)
        val last = LineCommands.deleteLines(EditorValue("a\nb\nc", 4))
        assertEquals("a\nb", last.text)
        assertEquals(3, last.cursor)
        assertEquals("", LineCommands.deleteLines(EditorValue("only", 1)).text)
    }

    @Test
    fun move_lines_up_and_down() {
        val up = LineCommands.moveLines(EditorValue("a\nb\nc", 2), up = true)
        assertEquals("b\na\nc", up.text)
        assertEquals(0, up.cursor)
        val down = LineCommands.moveLines(EditorValue("a\nb\nc", 2), up = false)
        assertEquals("a\nc\nb", down.text)
        assertEquals(4, down.cursor)
    }

    @Test
    fun moving_at_the_edges_changes_nothing() {
        val first = EditorValue("a\nb", 0)
        val last = EditorValue("a\nb", 3)
        assertEquals(first, LineCommands.moveLines(first, up = true))
        assertEquals(last, LineCommands.moveLines(last, up = false))
    }

    @Test
    fun toggle_comment_round_trips_in_kotlin() {
        val original = "val a = 1\n  val b = 2"
        val commented = LineCommands.toggleComment(EditorValue(original, 0, original.length), Language.KOTLIN)
        assertEquals("// val a = 1\n//   val b = 2", commented.text)
        val back = LineCommands.toggleComment(EditorValue(commented.text, 0, commented.text.length), Language.KOTLIN)
        assertEquals(original, back.text)
    }

    @Test
    fun toggle_comment_uses_the_languages_own_syntax() {
        assertEquals("# x = 1", LineCommands.toggleComment(EditorValue("x = 1", 0), Language.PYTHON).text)
        assertEquals("-- select 1", LineCommands.toggleComment(EditorValue("select 1", 0), Language.SQL).text)
        val html = LineCommands.toggleComment(EditorValue("<p>x</p>", 0), Language.HTML)
        assertEquals("<!-- <p>x</p> -->", html.text)
        assertEquals("<p>x</p>", LineCommands.toggleComment(EditorValue(html.text, 0), Language.HTML).text)
        assertEquals("/* a{} */", LineCommands.toggleComment(EditorValue("a{}", 0), Language.CSS).text)
    }

    @Test
    fun toggle_comment_skips_blank_lines_and_languages_without_comments() {
        val v = LineCommands.toggleComment(EditorValue("a\n\nb", 0, 4), Language.KOTLIN)
        assertEquals("// a\n\n// b", v.text)
        val json = EditorValue("{}", 0)
        assertEquals(json, LineCommands.toggleComment(json, Language.JSON))
        assertFalse(LineCommands.canToggleComment(Language.JSON))
        assertFalse(LineCommands.canToggleComment(Language.PLAIN))
        assertTrue(LineCommands.canToggleComment(Language.YAML))
    }

    @Test
    fun a_partly_commented_block_is_commented_again_not_uncommented() {
        assertEquals("// x\n// // y", LineCommands.toggleComment(EditorValue("x\n// y", 0, 6), Language.KOTLIN).text)
    }
}

class Phase11MultiCursorTest {
    private fun mv(text: String, vararg carets: Caret) = MultiValue(text, carets.toList())

    @Test
    fun insert_at_every_caret() {
        val r = MultiCursor.insert(mv("ab\ncd\nef", Caret(1), Caret(4), Caret(7)), "X")
        assertEquals("aXb\ncXd\neXf", r.text)
        assertEquals(listOf(Caret(2), Caret(6), Caret(10)), r.carets)
    }

    @Test
    fun insert_replaces_selections() {
        val r = MultiCursor.insert(mv("foo bar foo", Caret(0, 3), Caret(8, 11)), "x")
        assertEquals("x bar x", r.text)
        assertEquals(listOf(Caret(1), Caret(7)), r.carets)
    }

    @Test
    fun backspace_at_every_caret() {
        val r = MultiCursor.backspace(mv("abc", Caret(1), Caret(3)))
        assertEquals("b", r.text)
        assertEquals(listOf(Caret(0), Caret(1)), r.carets)
    }

    @Test
    fun backspace_at_the_start_does_nothing_and_selections_are_deleted() {
        assertEquals("abc", MultiCursor.backspace(mv("abc", Caret(0))).text)
        val r = MultiCursor.backspace(mv("abcd", Caret(1, 3)))
        assertEquals("ad", r.text)
        assertEquals(listOf(Caret(1)), r.carets)
    }

    @Test
    fun adjacent_carets_do_not_delete_twice_or_crash() {
        val r = MultiCursor.backspace(mv("abc", Caret(1), Caret(2)))
        assertEquals("c", r.text)
        assertEquals(listOf(Caret(0)), r.carets)
    }

    @Test
    fun backspace_removes_a_whole_emoji() {
        val text = "a\uD83D\uDE00b"
        assertEquals("ab", MultiCursor.backspace(mv(text, Caret(3))).text)
    }

    @Test
    fun delete_forward_at_every_caret() {
        val r = MultiCursor.deleteForward(mv("abc", Caret(0), Caret(2)))
        assertEquals("b", r.text)
    }

    @Test
    fun add_caret_below_and_above_keeps_the_column() {
        val below = MultiCursor.addCaretBelow(mv("abcd\nef\nghij", Caret(3)))
        assertEquals(listOf(Caret(3), Caret(7)), below.carets)
        val again = MultiCursor.addCaretBelow(below)
        assertEquals(listOf(Caret(3), Caret(7), Caret(10)), again.carets)
        assertEquals(again, MultiCursor.addCaretBelow(again))
        val above = MultiCursor.addCaretAbove(mv("abcd\nef\nghij", Caret(9)))
        assertEquals(listOf(Caret(6), Caret(9)), above.carets)
    }

    @Test
    fun select_next_occurrence_selects_the_word_then_adds_matches_and_stops() {
        val text = "foo bar foo"
        val first = MultiCursor.selectNextOccurrence(mv(text, Caret(1)))
        assertEquals(listOf(Caret(0, 3)), first.carets)
        val second = MultiCursor.selectNextOccurrence(first)
        assertEquals(listOf(Caret(0, 3), Caret(8, 11)), second.carets)
        assertEquals(second, MultiCursor.selectNextOccurrence(second))
    }

    @Test
    fun select_all_occurrences() {
        val text = "foo bar foo foo"
        val fromCaret = MultiCursor.selectAllOccurrences(mv(text, Caret(0)))
        assertEquals(listOf(Caret(0, 3), Caret(8, 11), Caret(12, 15)), fromCaret.carets)
        assertEquals(fromCaret.carets, MultiCursor.selectAllOccurrences(mv(text, Caret(8, 11))).carets)
    }

    @Test
    fun normalize_sorts_clamps_and_merges() {
        assertEquals(listOf(Caret(0, 8)), MultiCursor.normalize(listOf(Caret(0, 5), Caret(3, 8)), 20))
        assertEquals(listOf(Caret(2), Caret(9)), MultiCursor.normalize(listOf(Caret(9), Caret(2)), 20))
        assertEquals(listOf(Caret(0, 10)), MultiCursor.normalize(listOf(Caret(-5, 99)), 10))
        assertEquals(listOf(Caret(4)), MultiCursor.normalize(listOf(Caret(4), Caret(4)), 10))
    }

    @Test
    fun conversion_to_and_from_the_single_cursor_editor() {
        val m = MultiCursor.single(EditorValue("abc", 1, 2))
        assertEquals(listOf(Caret(1, 2)), m.carets)
        assertEquals(EditorValue("abc", 1, 2), MultiCursor.toEditorValue(m))
        assertEquals(EditorValue("abc", 0, 0), MultiCursor.toEditorValue(MultiValue("abc", emptyList())))
    }

    @Test
    fun the_number_of_carets_is_capped() {
        val many = (0..1000).map { Caret(it) }
        assertEquals(MultiCursor.MAX_CARETS, MultiCursor.normalize(many, 2000).size)
    }
}
