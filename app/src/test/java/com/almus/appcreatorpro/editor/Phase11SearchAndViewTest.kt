package com.almus.appcreatorpro.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11ProjectSearchTest {
    private val files = mapOf(
        "a.kt" to "val foo = 1\nFoo()\nfoobar",
        "b.txt" to "nothing",
        "bin.dat" to "x\u0000foo",
    )

    @Test
    fun default_search_ignores_case_and_skips_binary_files() {
        val out = ProjectSearch.search(files, "foo")
        assertNull(out.error)
        assertEquals(listOf(1 to 5, 2 to 1, 3 to 1), out.hits.map { it.line to it.column })
        assertTrue(out.hits.all { it.path == "a.kt" && it.length == 3 })
        assertEquals(2, out.filesSearched)
        assertFalse(out.truncated)
    }

    @Test
    fun case_sensitive_and_whole_word_options() {
        assertEquals(listOf(1, 3), ProjectSearch.search(files, "foo", SearchOptions(caseSensitive = true)).hits.map { it.line })
        assertEquals(listOf(1, 2), ProjectSearch.search(files, "foo", SearchOptions(wholeWord = true)).hits.map { it.line })
    }

    @Test
    fun regular_expressions_and_invalid_ones() {
        val ok = ProjectSearch.search(files, "\\w+\\(", SearchOptions(regex = true))
        assertEquals(1, ok.hits.size)
        assertEquals(4, ok.hits[0].length)
        val bad = ProjectSearch.search(files, "(", SearchOptions(regex = true))
        assertTrue(bad.hits.isEmpty())
        assertTrue(bad.error.orEmpty().startsWith("Invalid regular expression"))
    }

    @Test
    fun plain_queries_are_literal_and_empty_queries_find_nothing() {
        val out = ProjectSearch.search(mapOf("x.txt" to "axb a.b"), "a.b")
        assertEquals(listOf(5), out.hits.map { it.column })
        val none = ProjectSearch.search(files, "")
        assertTrue(none.hits.isEmpty())
        assertNull(none.error)
    }

    @Test
    fun zero_length_regex_matches_are_ignored() {
        assertTrue(ProjectSearch.search(mapOf("x" to "abc"), "x*", SearchOptions(regex = true)).hits.isEmpty())
    }

    @Test
    fun hit_text_is_trimmed_and_crlf_is_handled() {
        val out = ProjectSearch.search(mapOf("x.txt" to "   val foo\r\nbar"), "bar")
        assertEquals(2, out.hits[0].line)
        assertEquals("bar", out.hits[0].lineText)
        assertEquals("val foo", ProjectSearch.search(mapOf("x.txt" to "   val foo"), "foo").hits[0].lineText)
    }

    @Test
    fun total_and_per_file_limits_report_truncation() {
        val many = mapOf("x.txt" to (1..10).joinToString("\n") { "x" })
        val capped = ProjectSearch.search(many, "x", maxHits = 3)
        assertEquals(3, capped.hits.size)
        assertTrue(capped.truncated)
        val big = mapOf("y.txt" to (1..150).joinToString("\n") { "x" })
        val perFile = ProjectSearch.search(big, "x")
        assertEquals(ProjectSearch.MAX_PER_FILE, perFile.hits.size)
        assertTrue(perFile.truncated)
    }

    @Test
    fun results_are_ordered_by_path() {
        val out = ProjectSearch.search(mapOf("b.kt" to "hit", "a.kt" to "hit"), "hit")
        assertEquals(listOf("a.kt", "b.kt"), out.hits.map { it.path })
    }

    @Test
    fun replace_all_counts_matches_and_returns_only_changed_files() {
        val input = mapOf("a.kt" to "foo Foo foobar\nfoo", "b.kt" to "none")
        val r = ProjectSearch.replaceAll(input, "foo", "x")
        assertEquals(4, r.replacements)
        assertEquals(mapOf("a.kt" to "x x xbar\nx"), r.changed)
        val whole = ProjectSearch.replaceAll(input, "foo", "x", SearchOptions(wholeWord = true))
        assertEquals(3, whole.replacements)
        assertEquals("x x foobar\nx", whole.changed["a.kt"])
    }

    @Test
    fun replace_all_regex_groups_and_literal_dollar_signs() {
        val groups = ProjectSearch.replaceAll(mapOf("m.txt" to "me@host"), "(\\w+)@(\\w+)", "\$2 at \$1", SearchOptions(regex = true))
        assertEquals("host at me", groups.changed["m.txt"])
        val literal = ProjectSearch.replaceAll(mapOf("m.txt" to "a"), "a", "\$1")
        assertEquals("\$1", literal.changed["m.txt"])
    }

    @Test
    fun replace_all_keeps_crlf_respects_only_paths_and_reports_bad_regex() {
        assertEquals("z\r\nb", ProjectSearch.replaceAll(mapOf("c.txt" to "a\r\nb"), "a", "z").changed["c.txt"])
        val some = ProjectSearch.replaceAll(mapOf("a.kt" to "foo", "b.kt" to "foo"), "foo", "x", onlyPaths = setOf("b.kt"))
        assertEquals(setOf("b.kt"), some.changed.keys)
        assertNotNull(ProjectSearch.replaceAll(mapOf("a" to "x"), "(", "y", SearchOptions(regex = true)).error)
        assertTrue(ProjectSearch.replaceAll(mapOf("a" to "x"), "", "y").changed.isEmpty())
    }
}

class Phase11MinimapTest {
    @Test
    fun short_texts_get_one_row_per_line() {
        val rows = MinimapModel.build("a\nb\nc", 100)
        assertEquals(3, rows.size)
        assertEquals(listOf(0, 1, 2), rows.map { it.firstLine })
    }

    @Test
    fun long_texts_are_grouped_into_at_most_the_requested_rows() {
        val text = (1..10).joinToString("\n") { "x" }
        val rows = MinimapModel.build(text, 5)
        assertEquals(5, rows.size)
        assertEquals(0, rows[0].firstLine)
        assertEquals(1, rows[0].lastLine)
        assertEquals(9, rows.last().lastLine)
    }

    @Test
    fun density_reflects_how_full_the_lines_are() {
        val rows = MinimapModel.build("a".repeat(150) + "\n\nab", 100)
        assertEquals(1.0f, rows[0].density, 0.0001f)
        assertEquals(0.0f, rows[1].density, 0.0001f)
        assertEquals(0.02f, rows[2].density, 0.0001f)
    }

    @Test
    fun viewport_and_tap_mapping() {
        val (start, end) = MinimapModel.viewport(10, 20, 100)
        assertEquals(0.1f, start, 0.0001f)
        assertEquals(0.3f, end, 0.0001f)
        assertEquals(50, MinimapModel.lineAt(0.5f, 100))
        assertEquals(99, MinimapModel.lineAt(1f, 100))
        assertEquals(0, MinimapModel.lineAt(-1f, 100))
        assertEquals(0, MinimapModel.lineAt(0.5f, 0))
        val (s2, e2) = MinimapModel.viewport(95, 50, 100)
        assertEquals(0.95f, s2, 0.0001f)
        assertEquals(1.0f, e2, 0.0001f)
    }

    @Test
    fun the_minimap_is_for_wide_screens_only() {
        assertFalse(SplitLayout.showMinimap(411))
        assertTrue(SplitLayout.showMinimap(800))
    }
}

class Phase11ThemeTest {
    @Test
    fun colours_parse_and_format() {
        assertEquals(0xFFFF0000L, EditorThemes.parseColor("#FF0000"))
        assertEquals(0xFFFF0000L, EditorThemes.parseColor("f00"))
        assertEquals(0x80112233L, EditorThemes.parseColor("#80112233"))
        assertNull(EditorThemes.parseColor("zzz"))
        assertNull(EditorThemes.parseColor("#12345"))
        assertNull(EditorThemes.parseColor(""))
        assertEquals("#FF112233", EditorThemes.formatColor(0xFF112233L))
    }

    @Test
    fun contrast_ratio_matches_the_wcag_extremes() {
        assertEquals(21.0, EditorThemes.contrastRatio(0xFF000000L, 0xFFFFFFFFL), 0.01)
        assertEquals(1.0, EditorThemes.contrastRatio(0xFF123456L, 0xFF123456L), 0.0001)
    }

    @Test
    fun built_in_themes_are_unique_readable_and_cover_dark_and_light() {
        assertEquals(EditorThemes.BUILT_IN.size, EditorThemes.BUILT_IN.map { it.id }.toSet().size)
        assertTrue(EditorThemes.BUILT_IN.any { it.isDark })
        assertTrue(EditorThemes.BUILT_IN.any { !it.isDark })
        for (t in EditorThemes.BUILT_IN) assertEquals(t.id, emptyList<String>(), EditorThemes.readabilityProblems(t))
    }

    @Test
    fun lookup_falls_back_to_the_system_mode() {
        assertEquals(EditorThemes.SOLARIZED_DARK, EditorThemes.byId("solarized_dark", systemDark = false))
        assertEquals(EditorThemes.ALMUS_DARK, EditorThemes.byId("deleted", systemDark = true))
        assertEquals(EditorThemes.ALMUS_LIGHT, EditorThemes.byId(null, systemDark = false))
        val mine = EditorThemes.custom("mine", "Mine", EditorThemes.ALMUS_DARK, mapOf("keyword" to "#FF0000")).theme
        assertEquals(mine, EditorThemes.byId("mine", systemDark = false, custom = listOfNotNull(mine)))
    }

    @Test
    fun a_custom_theme_overrides_only_what_is_given() {
        val r = EditorThemes.custom("mine", "Mine", EditorThemes.ALMUS_DARK, mapOf("keyword" to "#FF0000"))
        val t = r.theme!!
        assertEquals(0xFFFF0000L, t.keyword)
        assertEquals(EditorThemes.ALMUS_DARK.string, t.string)
        assertEquals("mine", t.id)
        assertEquals(0xFFFF0000L, t.colorFor(TokenType.KEYWORD))
        assertTrue(r.problems.isEmpty())
    }

    @Test
    fun custom_themes_reject_bad_input_with_reasons() {
        val builtInId = EditorThemes.custom(EditorThemes.ALMUS_DARK.id, "X", EditorThemes.ALMUS_DARK, emptyMap())
        assertNull(builtInId.theme)
        assertNull(EditorThemes.custom("", "X", EditorThemes.ALMUS_DARK, emptyMap()).theme)
        val unknown = EditorThemes.custom("a", "A", EditorThemes.ALMUS_DARK, mapOf("sparkle" to "#FFFFFF"))
        assertNull(unknown.theme)
        assertEquals(listOf("Unknown colour: sparkle"), unknown.problems)
        val notColour = EditorThemes.custom("a", "A", EditorThemes.ALMUS_DARK, mapOf("keyword" to "red"))
        assertNull(notColour.theme)
        assertTrue(notColour.problems[0].startsWith("Not a colour"))
    }

    @Test
    fun unreadable_custom_colours_still_build_but_are_flagged() {
        val r = EditorThemes.custom("dim", "Dim", EditorThemes.ALMUS_DARK, mapOf("keyword" to "#101010"))
        assertNotNull(r.theme)
        assertTrue(r.problems.any { it.startsWith("keyword") })
    }
}

class Phase11ShortcutAndLayoutTest {
    @Test
    fun chords_parse_in_any_order_and_print_in_one() {
        assertEquals(KeyChord("P", ctrl = true, shift = true), KeyChord.parse("shift+Ctrl+p"))
        assertEquals(KeyChord("S", ctrl = true), KeyChord.parse("Cmd+S"))
        assertEquals("Ctrl+Alt+Shift+K", KeyChord("K", ctrl = true, shift = true, alt = true).toString())
        assertNull(KeyChord.parse(""))
        assertNull(KeyChord.parse("Ctrl+"))
        assertNull(KeyChord.parse("Ctrl+A+B"))
        assertNull(KeyChord.parse("Ctrl+Shift"))
    }

    @Test
    fun the_default_bindings_resolve_and_have_no_duplicates() {
        val m = ShortcutMap.defaults()
        assertEquals(EditorCommand.SAVE, m.resolve(KeyChord("S", ctrl = true)))
        assertEquals(EditorCommand.GO_TO_LINE, m.resolve(KeyChord("G", ctrl = true)))
        assertEquals(EditorCommand.REDO, m.resolve(KeyChord("Y", ctrl = true)))
        assertEquals(EditorCommand.REDO, m.resolve(KeyChord("Z", ctrl = true, shift = true)))
        assertEquals(EditorCommand.SELECT_NEXT_OCCURRENCE, m.resolve(KeyChord("D", ctrl = true)))
        assertNull(m.resolve(KeyChord("Q", ctrl = true)))
        assertEquals(KeyChord("S", ctrl = true), m.chordFor(EditorCommand.SAVE))
        // Every command has a default shortcut, and every shortcut belongs to one command.
        assertEquals(EditorCommand.values().toSet(), m.all().values.toSet())
    }

    @Test
    fun rebinding_reports_conflicts_instead_of_overwriting() {
        val m = ShortcutMap.defaults()
        assertNull(m.rebind(EditorCommand.FIND, KeyChord("S", ctrl = true)))
        val moved = m.rebind(EditorCommand.FIND, KeyChord("F3"))!!
        assertEquals(EditorCommand.FIND, moved.resolve(KeyChord("F3")))
        assertNull(moved.resolve(KeyChord("F", ctrl = true)))
        assertEquals(EditorCommand.SAVE, m.conflict(KeyChord("S", ctrl = true), EditorCommand.FIND))
        assertNull(m.conflict(KeyChord("S", ctrl = true), EditorCommand.SAVE))
    }

    @Test
    fun split_open_swap_and_unsplit() {
        var l = SplitLayout(primary = "a.kt")
        assertFalse(l.isSplit)
        l = l.split("b.kt")
        assertTrue(l.isSplit)
        assertEquals("b.kt", l.focusedPath)
        l = l.open("c.kt")
        assertEquals("a.kt", l.primary)
        assertEquals("c.kt", l.secondary)
        l = l.swap()
        assertEquals("c.kt", l.primary)
        assertEquals("a.kt", l.secondary)
        l = l.unsplit()
        assertNull(l.secondary)
        assertEquals("c.kt", l.focusedPath)
    }

    @Test
    fun split_without_a_target_shows_the_same_file_and_needs_a_file() {
        assertEquals("a.kt", SplitLayout(primary = "a.kt").split().secondary)
        assertFalse(SplitLayout().split("b.kt").isSplit)
    }

    @Test
    fun closing_and_renaming_files_keep_the_panes_consistent() {
        val l = SplitLayout(primary = "a.kt", secondary = "b.kt")
        assertEquals(SplitLayout(primary = "a.kt"), l.onClosed("b.kt", listOf("a.kt")))
        assertEquals("c.kt", l.onClosed("a.kt", listOf("b.kt", "c.kt")).primary)
        assertEquals("c.kt", SplitLayout(primary = "a.kt").onClosed("a.kt", listOf("c.kt")).primary)
        assertEquals("z.kt", l.onRenamed("b.kt", "z.kt").secondary)
    }

    @Test
    fun ratio_orientation_and_focus_are_bounded() {
        val l = SplitLayout(primary = "a", secondary = "b")
        assertEquals(0.2f, l.withRatio(0f).ratio, 0.0001f)
        assertEquals(0.8f, l.withRatio(5f).ratio, 0.0001f)
        assertEquals(SplitOrientation.STACKED, l.toggleOrientation().orientation)
        assertFalse(SplitLayout(primary = "a").focus(true).focusSecondary)
        assertEquals(SplitOrientation.STACKED, l.suggestedOrientation(411, 900))
        assertEquals(SplitOrientation.SIDE_BY_SIDE, l.suggestedOrientation(900, 600))
        assertEquals(SplitOrientation.SIDE_BY_SIDE, l.suggestedOrientation(700, 400))
        assertEquals(SplitOrientation.STACKED, l.suggestedOrientation(700, 1000))
    }
}
