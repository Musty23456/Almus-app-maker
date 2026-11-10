package com.almus.appcreatorpro.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun src(vararg l: String) = l.joinToString("\n")

private fun folds(text: String, lang: Language) = FoldingEngine.regions(text, lang).map { it.startLine to it.endLine }

class Phase11FoldingTest {
    @Test
    fun kotlin_braces_nest_and_strings_and_comments_are_ignored() {
        val code = src(
            "fun a() {",
            "    if (x) {",
            "        y()",
            "    }",
            "}",
            "/* c",
            " d */",
            "val s = \"{\"",
        )
        assertEquals(listOf(0 to 4, 1 to 3, 5 to 6), folds(code, Language.KOTLIN))
    }

    @Test
    fun parentheses_over_several_lines_fold_too() {
        val code = src("foo(", "    1,", "    2) {", "    x", "}")
        assertEquals(listOf(0 to 2, 2 to 4), folds(code, Language.KOTLIN))
    }

    @Test
    fun the_largest_region_wins_when_two_start_on_the_same_line() {
        val code = src("a { b(", " c", " )", "}")
        assertEquals(listOf(0 to 3), folds(code, Language.KOTLIN))
    }

    @Test
    fun unbalanced_braces_give_no_region() {
        assertTrue(folds("fun a() {\n x", Language.KOTLIN).isEmpty())
        assertTrue(folds("x }\n y }", Language.KOTLIN).isEmpty())
    }

    @Test
    fun json_arrays_and_objects_fold() {
        assertEquals(listOf(0 to 4, 1 to 3), folds("{\n \"a\": [\n 1\n ]\n}", Language.JSON))
    }

    @Test
    fun python_folds_by_indentation_and_ignores_blank_lines() {
        val code = src("def a():", "    x = 1", "    if x:", "        pass", "", "    y = 2", "z = 3")
        assertEquals(listOf(0 to 5, 2 to 3), folds(code, Language.PYTHON))
    }

    @Test
    fun yaml_folds_by_indentation() {
        assertEquals(listOf(0 to 3, 2 to 3), folds("a:\n  b: 1\n  c:\n    d: 2\ne: 3", Language.YAML))
    }

    @Test
    fun html_tags_fold_void_elements_and_multi_line_comments() {
        val html = src("<div>", "  <p>a</p>", "  <br>", "  <ul>", "    <li>x</li>", "  </ul>", "</div>", "<!-- a", "b -->")
        assertEquals(listOf(0 to 6, 3 to 5, 7 to 8), folds(html, Language.HTML))
    }

    @Test
    fun markdown_sections_and_fenced_code_fold() {
        assertEquals(listOf(0 to 3, 2 to 3), folds("# A\ntext\n## B\nmore\n# C", Language.MARKDOWN))
        assertEquals(listOf(0 to 3), folds("```\nx\ny\n```", Language.MARKDOWN))
    }

    @Test
    fun plain_text_and_empty_text_have_no_regions() {
        assertTrue(folds("a\nb", Language.PLAIN).isEmpty())
        assertTrue(folds("", Language.KOTLIN).isEmpty())
    }

    @Test
    fun visible_lines_hide_the_folded_body_but_keep_the_first_line() {
        val regions = listOf(FoldRegion(0, 4), FoldRegion(1, 3))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), FoldingEngine.visibleLines(7, regions, emptySet()))
        assertEquals(listOf(0, 1, 4, 5, 6), FoldingEngine.visibleLines(7, regions, setOf(1)))
        assertEquals(listOf(0, 5, 6), FoldingEngine.visibleLines(7, regions, setOf(0)))
    }

    @Test
    fun toggle_and_retain() {
        assertEquals(setOf(3), FoldingEngine.toggle(emptySet(), 3))
        assertEquals(emptySet<Int>(), FoldingEngine.toggle(setOf(3), 3))
        assertEquals(setOf(1), FoldingEngine.retain(setOf(1, 9), listOf(FoldRegion(0, 2), FoldRegion(1, 3))))
        assertEquals(FoldRegion(1, 3), FoldingEngine.regionStartingAt(listOf(FoldRegion(0, 2), FoldRegion(1, 3)), 1))
        assertNull(FoldingEngine.regionStartingAt(listOf(FoldRegion(0, 2)), 5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_region_needs_two_lines() {
        FoldRegion(2, 2)
    }
}

class Phase11CompletionTest {
    @Test
    fun prefix_is_the_identifier_before_the_caret() {
        assertEquals("foo_bar", CompletionEngine.prefixAt("val foo_bar", 11))
        assertEquals("b", CompletionEngine.prefixAt("a.b", 3))
        assertEquals("", CompletionEngine.prefixAt("a ", 2))
        assertEquals("back-gr", CompletionEngine.prefixAt("color: back-gr", 14, Language.CSS))
        assertEquals("back", CompletionEngine.prefixAt("color: back-gr", 11, Language.KOTLIN))
    }

    @Test
    fun match_scores_rank_exact_case_first_then_ignore_case_then_humps_then_letters() {
        assertEquals(100, CompletionEngine.matchScore("return", "ret"))
        assertEquals(80, CompletionEngine.matchScore("Return", "ret"))
        assertEquals(50, CompletionEngine.matchScore("getTotalLength", "gtl"))
        assertEquals(20, CompletionEngine.matchScore("abc", "ac"))
        assertEquals(0, CompletionEngine.matchScore("abc", "z"))
        assertEquals(0, CompletionEngine.matchScore("abc", "zz"))
    }

    @Test
    fun words_from_the_file_are_offered() {
        val text = "val total = 1\nto"
        val result = CompletionEngine.suggest(text, text.length, Language.KOTLIN)
        assertEquals("total", result.first().label)
        assertEquals(CompletionKind.WORD, result.first().kind)
    }

    @Test
    fun keywords_are_offered() {
        val result = CompletionEngine.suggest("ret", 3, Language.KOTLIN)
        assertEquals("return", result.first().label)
        assertEquals(CompletionKind.KEYWORD, result.first().kind)
    }

    @Test
    fun snippets_are_offered() {
        val result = CompletionEngine.suggest("fore", 4, Language.KOTLIN)
        assertEquals("foreach", result.first().label)
        assertEquals(CompletionKind.SNIPPET, result.first().kind)
    }

    @Test
    fun project_symbols_match_by_camel_hump_initials() {
        val result = CompletionEngine.suggest("gtl", 3, Language.KOTLIN, extraSymbols = listOf("getTotalLength"))
        assertTrue(result.any { it.label == "getTotalLength" && it.kind == CompletionKind.SYMBOL })
    }

    @Test
    fun the_word_being_typed_is_not_offered_back() {
        val result = CompletionEngine.suggest("zzzq", 4, Language.KOTLIN)
        assertTrue(result.none { it.label == "zzzq" })
    }

    @Test
    fun nothing_is_offered_inside_comments_and_strings() {
        assertTrue(CompletionEngine.suggest("// ret", 6, Language.KOTLIN).isEmpty())
        assertTrue(CompletionEngine.suggest("val s = \"ret\"", 12, Language.KOTLIN).isEmpty())
    }

    @Test
    fun an_empty_prefix_offers_nothing_unless_forced_and_plain_text_never_does() {
        val text = "val x = "
        assertTrue(CompletionEngine.suggest(text, text.length, Language.KOTLIN).isEmpty())
        val forced = CompletionEngine.suggest(text, text.length, Language.KOTLIN, limit = 5, force = true)
        assertTrue(forced.isNotEmpty())
        assertTrue(forced.size <= 5)
        assertTrue(CompletionEngine.suggest("ret", 3, Language.PLAIN).isEmpty())
    }

    @Test
    fun the_limit_is_respected() {
        assertTrue(CompletionEngine.suggest("c", 1, Language.KOTLIN, limit = 3).size <= 3)
    }

    @Test
    fun applying_a_keyword_replaces_the_prefix() {
        val v = CompletionEngine.apply(EditorValue("ret", 3), Completion("return", "return", CompletionKind.KEYWORD))
        assertEquals("return", v.text)
        assertEquals(6, v.cursor)
        assertTrue(v.isCollapsed)
    }

    @Test
    fun applying_a_snippet_indents_following_lines_and_places_the_caret() {
        val snippet = Completion("foreach", "for (item in ${CompletionEngine.CARET}) {\n    \n}", CompletionKind.SNIPPET)
        val v = CompletionEngine.apply(EditorValue("fun f() {\n    fore", 18), snippet)
        assertEquals("fun f() {\n    for (item in ) {\n        \n    }", v.text)
        assertEquals(27, v.cursor)
    }

    @Test
    fun applying_works_in_the_middle_of_a_line() {
        val v = CompletionEngine.apply(EditorValue("x ret)", 5), Completion("return", "return", CompletionKind.KEYWORD))
        assertEquals("x return)", v.text)
        assertEquals(8, v.cursor)
        assertFalse(v.text.contains(CompletionEngine.CARET))
    }
}
