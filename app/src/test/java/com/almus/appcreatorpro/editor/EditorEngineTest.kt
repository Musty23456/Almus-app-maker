package com.almus.appcreatorpro.editor

import com.almus.appcreatorpro.domain.model.SourceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ev(text: String, a: Int = text.length, b: Int = a) = EditorValue(text, a, b)

class EditorValueTest {
    @Test
    fun replace_selection_puts_caret_after_insert() {
        val v = ev("hello world", 6, 11).replaceSelection("there")
        assertEquals("hello there", v.text)
        assertEquals(11, v.cursor)
        assertTrue(v.isCollapsed)
    }

    @Test
    fun reversed_selection_and_out_of_range_values_are_clamped() {
        val v = EditorValue("abc", 99, -5)
        assertEquals(0, v.selMin)
        assertEquals(3, v.selMax)
        assertEquals("abc", v.selectedText)
        assertEquals(EditorValue("abc", 3, 0), v.coerced())
    }
}

class UndoHistoryTest {
    private var now = 0L
    private fun history(coalesce: Long = 1_000) = UndoHistory(coalesceMillis = coalesce, clock = { now })

    @Test
    fun undo_and_redo_restore_values() {
        val h = history()
        h.record(ev(""), ev("a"))
        now += 5_000
        h.record(ev("a"), ev("ab"))

        assertEquals(ev("a"), h.undo(ev("ab")))
        assertEquals(ev(""), h.undo(ev("a")))
        assertNull(h.undo(ev("")))
        assertEquals(ev("a"), h.redo(ev("")))
        assertEquals(ev("ab"), h.redo(ev("a")))
        assertNull(h.redo(ev("ab")))
    }

    @Test
    fun fast_typing_is_merged_into_one_step() {
        val h = history()
        var current = ev("")
        for (c in "hello") {
            val next = ev(current.text + c)
            h.record(current, next)
            current = next
            now += 100
        }
        assertEquals(ev(""), h.undo(current))
        assertFalse(h.canUndo)
    }

    @Test
    fun pause_or_newline_starts_a_new_step() {
        val h = history()
        h.record(ev(""), ev("a"))
        now += 5_000
        h.record(ev("a"), ev("ab"))
        now += 100
        h.record(ev("ab"), ev("ab\n"))
        assertEquals(ev("ab"), h.undo(ev("ab\n")))
        assertEquals(ev("a"), h.undo(ev("ab")))
    }

    @Test
    fun new_edit_clears_redo() {
        val h = history()
        h.record(ev(""), ev("a"))
        h.undo(ev("a"))
        assertTrue(h.canRedo)
        now += 10
        h.record(ev(""), ev("z"))
        assertFalse(h.canRedo)
    }

    @Test
    fun selection_only_changes_are_not_recorded() {
        val h = history()
        h.record(ev("abc", 0), ev("abc", 3))
        assertFalse(h.canUndo)
    }

    @Test
    fun history_is_bounded() {
        val h = UndoHistory(maxEntries = 3, coalesceMillis = 0, clock = { now })
        for (i in 1..10) {
            now += 10
            h.record(ev("x".repeat(i)), ev("x".repeat(i + 5)))
        }
        var steps = 0
        var cur = ev("x".repeat(15))
        while (true) {
            cur = h.undo(cur) ?: break
            steps++
        }
        assertEquals(3, steps)
    }

    @Test
    fun character_budget_drops_oldest_snapshots() {
        val h = UndoHistory(maxChars = 100, coalesceMillis = 0, clock = { now })
        for (i in 1..10) {
            now += 10
            h.record(ev("y".repeat(60)), ev("y".repeat(60 + i * 3)))
        }
        var steps = 0
        var cur = ev("z")
        while (true) {
            cur = h.undo(cur) ?: break
            steps++
        }
        assertTrue(steps in 1..2)
    }
}

class SyntaxHighlighterTest {
    private fun kinds(text: String, lang: Language) =
        SyntaxHighlighter.tokenize(text, lang).map { text.substring(it.start, it.end) to it.type }

    @Test
    fun kotlin_keywords_strings_comments_numbers_annotations() {
        val code = "@Composable fun greet() { val n = 42 // hi\n val s = \"a\\\"b\" /* x */ }"
        val k = kinds(code, Language.KOTLIN)
        assertTrue("@Composable" to TokenType.ANNOTATION in k)
        assertTrue("fun" to TokenType.KEYWORD in k)
        assertTrue("val" to TokenType.KEYWORD in k)
        assertTrue("42" to TokenType.NUMBER in k)
        assertTrue("// hi" to TokenType.COMMENT in k)
        assertTrue("\"a\\\"b\"" to TokenType.STRING in k)
        assertTrue("/* x */" to TokenType.COMMENT in k)
    }

    @Test
    fun keywords_inside_strings_and_comments_are_not_highlighted() {
        val k = kinds("// val fun\n\"val\"", Language.KOTLIN)
        assertEquals(listOf(TokenType.COMMENT, TokenType.STRING), k.map { it.second })
    }

    @Test
    fun identifiers_containing_keywords_are_not_keywords() {
        assertTrue(kinds("value valid iffy", Language.KOTLIN).isEmpty())
    }

    @Test
    fun unterminated_string_and_block_comment_do_not_hang_or_crash() {
        assertEquals(1, kinds("\"never closed\nnext", Language.JAVA).count { it.second == TokenType.STRING })
        assertEquals(TokenType.COMMENT, kinds("/* open", Language.JAVA).single().second)
    }

    @Test
    fun kotlin_raw_strings_span_lines() {
        val k = kinds("val s = \"\"\"a\nb\"\"\"", Language.KOTLIN)
        assertTrue(k.any { it.second == TokenType.STRING && it.first.contains("\n") })
    }

    @Test
    fun javascript_template_strings_and_keywords() {
        val k = kinds("const a = `x\ny`;", Language.JAVASCRIPT)
        assertTrue("const" to TokenType.KEYWORD in k)
        assertTrue(k.any { it.second == TokenType.STRING && it.first.startsWith("`") })
    }

    @Test
    fun css_colors_and_comments() {
        val k = kinds("a { color: #ff00aa; /* c */ }", Language.CSS)
        assertTrue("#ff00aa" to TokenType.NUMBER in k)
        assertTrue("/* c */" to TokenType.COMMENT in k)
    }

    @Test
    fun json_literals() {
        val k = kinds("{\"a\": true, \"b\": null, \"c\": 12}", Language.JSON)
        assertTrue("true" to TokenType.KEYWORD in k)
        assertTrue("12" to TokenType.NUMBER in k)
        assertEquals(3, k.count { it.second == TokenType.STRING })
    }

    @Test
    fun html_tags_attributes_strings_and_comments() {
        val html = "<!-- note --><a href=\"x\" id='y'>text</a>"
        val k = kinds(html, Language.HTML)
        assertTrue("<!-- note -->" to TokenType.COMMENT in k)
        assertTrue("<a" to TokenType.TAG in k)
        assertTrue("href" to TokenType.ATTRIBUTE in k)
        assertTrue("\"x\"" to TokenType.STRING in k)
        assertTrue("</a" to TokenType.TAG in k)
    }

    @Test
    fun tokens_are_ordered_and_never_overlap() {
        val sources = listOf(
            Language.KOTLIN to "fun main() { println(\"hi\") // c\n }",
            Language.HTML to "<div class=\"a\"><p>x</p></div><!-- c -->",
            Language.JAVASCRIPT to "let a = 'x'; /* c */ a++;",
        )
        for ((lang, text) in sources) {
            val tokens = SyntaxHighlighter.tokenize(text, lang)
            var last = 0
            for (t in tokens) {
                assertTrue(t.start >= last && t.end > t.start && t.end <= text.length)
                last = t.end
            }
        }
    }

    @Test
    fun plain_and_markdown_have_no_tokens_and_huge_files_are_skipped() {
        assertTrue(SyntaxHighlighter.tokenize("fun val", Language.PLAIN).isEmpty())
        assertTrue(SyntaxHighlighter.tokenize("fun val", Language.MARKDOWN).isEmpty())
        val huge = "val ".repeat(SyntaxHighlighter.MAX_HIGHLIGHT_CHARS)
        assertTrue(SyntaxHighlighter.tokenize(huge, Language.KOTLIN).isEmpty())
    }

    @Test
    fun language_is_detected_from_file_names() {
        assertEquals(Language.KOTLIN, Language.fromFileName("app/src/Main.kt"))
        assertEquals(Language.KOTLIN, Language.fromFileName("build.gradle.kts"))
        assertEquals(Language.JAVA, Language.fromFileName("A.java"))
        assertEquals(Language.XML, Language.fromFileName("AndroidManifest.xml"))
        assertEquals(Language.HTML, Language.fromFileName("INDEX.HTML"))
        assertEquals(Language.JAVASCRIPT, Language.fromFileName("script.js"))
        assertEquals(Language.PLAIN, Language.fromFileName("Makefile"))
        assertEquals(Language.PLAIN, Language.fromFileName("README"))
    }
}

class BracketMatcherTest {
    @Test
    fun matches_forward_and_backward() {
        val text = "f(a[1], {b})"
        assertEquals(1 to 11, BracketMatcher.findMatch(text, 2))   // caret after '('
        assertEquals(11 to 1, BracketMatcher.findMatch(text, 12))  // caret after ')'
        assertEquals(3 to 5, BracketMatcher.findMatch(text, 3))    // caret before '[' (char at cursor)
    }

    @Test
    fun handles_nesting() {
        assertEquals(0 to 5, BracketMatcher.findMatch("((()))", 0))
        assertEquals(1 to 4, BracketMatcher.findMatch("((()))", 2))
    }

    @Test
    fun unbalanced_or_no_bracket_returns_null() {
        assertNull(BracketMatcher.findMatch("(abc", 1))
        assertNull(BracketMatcher.findMatch("abc)", 4))
        assertNull(BracketMatcher.findMatch("abc", 1))
        assertNull(BracketMatcher.findMatch("", 0))
    }

    @Test
    fun mismatched_kinds_do_not_match() {
        assertNull(BracketMatcher.findMatch("(]", 1))
    }
}

class CodeEditingTest {
    @Test
    fun newline_keeps_previous_indentation() {
        val old = ev("    foo()")
        val typed = ev("    foo()\n")
        val out = CodeEditing.afterInput(old, typed)
        assertEquals("    foo()\n    ", out.text)
        assertEquals(out.text.length, out.cursor)
    }

    @Test
    fun newline_after_open_brace_adds_a_level() {
        val out = CodeEditing.afterInput(ev("fun a() {"), ev("fun a() {\n"))
        assertEquals("fun a() {\n    ", out.text)
    }

    @Test
    fun newline_between_braces_puts_closer_on_its_own_line() {
        val old = EditorValue("if (x) {}", 8)
        val typed = EditorValue("if (x) {\n}", 9)
        val out = CodeEditing.afterInput(old, typed)
        assertEquals("if (x) {\n    \n}", out.text)
        assertEquals("if (x) {\n    ".length, out.cursor)
    }

    @Test
    fun newline_in_the_middle_of_a_line_still_indents() {
        val old = EditorValue("  ab", 3)
        val typed = EditorValue("  a\nb", 4)
        assertEquals("  a\n  b", CodeEditing.afterInput(old, typed).text)
    }

    @Test
    fun other_edits_are_unchanged() {
        val old = ev("ab")
        assertEquals(ev("abc"), CodeEditing.afterInput(old, ev("abc")))
        assertEquals(ev("a"), CodeEditing.afterInput(old, ev("a")))
        val pasted = ev("ab\n\n")
        assertEquals(pasted, CodeEditing.afterInput(old, pasted))
    }

    @Test
    fun tab_with_caret_inserts_indent() {
        val out = CodeEditing.indent(EditorValue("ab", 1))
        assertEquals("a    b", out.text)
        assertEquals(5, out.cursor)
    }

    @Test
    fun tab_with_selection_indents_all_touched_lines_and_keeps_selection() {
        val text = "one\ntwo\nthree"
        val out = CodeEditing.indent(EditorValue(text, 2, 9)) // from inside "one" to inside "three"
        assertEquals("    one\n    two\n    three", out.text)
        assertEquals(6, out.selStart)   // 2 + one indent
        assertEquals(21, out.selEnd)    // 9 + three indents
    }

    @Test
    fun indent_skips_blank_lines() {
        val out = CodeEditing.indent(EditorValue("a\n\nb", 0, 4))
        assertEquals("    a\n\n    b", out.text)
    }

    @Test
    fun selection_ending_after_a_newline_does_not_touch_the_next_line() {
        val out = CodeEditing.indent(EditorValue("a\nb", 0, 2))
        assertEquals("    a\nb", out.text)
    }

    @Test
    fun shift_tab_removes_up_to_one_level() {
        assertEquals("x", CodeEditing.outdent(EditorValue("    x", 5)).text)
        assertEquals("  x", CodeEditing.outdent(EditorValue("      x", 7)).text)
        assertEquals("x", CodeEditing.outdent(EditorValue(" x", 2)).text)
        assertEquals("x", CodeEditing.outdent(EditorValue("\tx", 2)).text)
        assertEquals("x", CodeEditing.outdent(EditorValue("x", 1)).text)
    }

    @Test
    fun outdent_moves_caret_with_the_text() {
        val out = CodeEditing.outdent(EditorValue("    abc", 6))
        assertEquals("abc", out.text)
        assertEquals(2, out.cursor)
        val atStart = CodeEditing.outdent(EditorValue("    abc", 2))
        assertEquals(0, atStart.cursor)
    }

    @Test
    fun outdent_applies_to_every_selected_line() {
        val out = CodeEditing.outdent(EditorValue("    a\n    b\nc", 0, 13))
        assertEquals("a\nb\nc", out.text)
    }
}

class FindEngineTest {
    @Test
    fun finds_all_non_overlapping_matches() {
        assertEquals(listOf(0..1, 2..3, 4..5), FindEngine.findAll("aaaaaa", "aa", true))
        assertTrue(FindEngine.findAll("abc", "", true).isEmpty())
        assertTrue(FindEngine.findAll("abc", "z", true).isEmpty())
    }

    @Test
    fun case_sensitivity_is_respected() {
        assertEquals(1, FindEngine.findAll("Foo foo FOO", "foo", true).size)
        assertEquals(3, FindEngine.findAll("Foo foo FOO", "foo", false).size)
    }

    @Test
    fun next_and_previous_wrap_around() {
        val m = listOf(0..1, 5..6, 9..10)
        assertEquals(1, FindEngine.nextIndex(m, 2))
        assertEquals(0, FindEngine.nextIndex(m, 11))
        assertEquals(1, FindEngine.previousIndex(m, 9))
        assertEquals(2, FindEngine.previousIndex(m, 0))
    }

    @Test
    fun replace_all_counts_and_handles_replacement_containing_query() {
        assertEquals("xbxb" to 2, FindEngine.replaceAll("abab", "a", "x", true))
        assertEquals("aab aab" to 2, FindEngine.replaceAll("ab ab", "a", "aa", true))
        assertEquals("xx x" to 3, FindEngine.replaceAll("Aa a", "a", "x", false))
        assertEquals("same" to 0, FindEngine.replaceAll("same", "zzz", "q", true))
        assertEquals("same" to 0, FindEngine.replaceAll("same", "", "q", true))
        assertEquals("ac" to 1, FindEngine.replaceAll("abc", "b", "", true))
    }
}

class FileTreeTest {
    private fun files(vararg p: String) = p.map { SourceFileEntry(it, 1) }

    @Test
    fun folders_come_first_and_everything_is_sorted() {
        val rows = FileTree.rows(files("b.txt", "src/z.kt", "src/a.kt", "A.md", "res/x.xml"), emptySet())
        assertEquals(
            listOf("res", "res/x.xml", "src", "src/a.kt", "src/z.kt", "A.md", "b.txt"),
            rows.map { it.path },
        )
        assertEquals(listOf(0, 1, 0, 1, 1, 0, 0), rows.map { it.depth })
    }

    @Test
    fun collapsed_folders_hide_their_children() {
        val rows = FileTree.rows(files("src/a/b.kt", "src/c.kt", "top.txt"), setOf("src/a"))
        assertEquals(listOf("src", "src/a", "src/c.kt", "top.txt"), rows.map { it.path })
        val folder = rows.filterIsInstance<TreeRow.Folder>().first { it.path == "src/a" }
        assertFalse(folder.expanded)
    }

    @Test
    fun empty_list_gives_no_rows() {
        assertTrue(FileTree.rows(emptyList(), emptySet()).isEmpty())
    }
}
