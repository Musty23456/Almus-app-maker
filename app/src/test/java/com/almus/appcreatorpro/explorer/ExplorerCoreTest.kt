package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.domain.model.SourceFileEntry
import com.almus.appcreatorpro.editor.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun f(path: String, size: Long = 100) = SourceFileEntry(path, size)

class FileTypesTest {
    @Test
    fun recognizes_the_requested_development_files() {
        val expected = mapOf(
            "app/src/Main.kt" to FileKind.KOTLIN,
            "A.java" to FileKind.JAVA,
            "res/layout/a.xml" to FileKind.XML,
            "data.json" to FileKind.JSON,
            "ci.yml" to FileKind.YAML,
            "ci.yaml" to FileKind.YAML,
            "app/build.gradle.kts" to FileKind.GRADLE,
            "settings.gradle" to FileKind.GRADLE,
            "tool.py" to FileKind.PYTHON,
            "a.js" to FileKind.JAVASCRIPT,
            "a.ts" to FileKind.TYPESCRIPT,
            "index.html" to FileKind.HTML,
            "style.css" to FileKind.CSS,
            "README.md" to FileKind.MARKDOWN,
        )
        for ((path, kind) in expected) assertEquals(path, kind, FileTypes.recognize(path))
    }

    @Test
    fun gradle_kts_is_gradle_but_other_kts_is_kotlin() {
        assertEquals(FileKind.GRADLE, FileTypes.recognize("build.gradle.kts"))
        assertEquals(FileKind.KOTLIN, FileTypes.recognize("script.kts"))
    }

    @Test
    fun special_names_and_unknown_files() {
        assertEquals(FileKind.TEXT, FileTypes.recognize(".gitignore"))
        assertEquals(FileKind.PROPERTIES, FileTypes.recognize("gradle.properties"))
        assertEquals(FileKind.PROPERTIES, FileTypes.recognize("gradle/libs.versions.toml"))
        assertEquals(FileKind.OTHER, FileTypes.recognize("weird.xyz"))
        assertEquals(FileKind.OTHER, FileTypes.recognize("noextension"))
    }

    @Test
    fun binary_detection_treats_svg_as_text() {
        assertTrue(FileTypes.isBinary("logo.png"))
        assertTrue(FileTypes.isBinary("libs/a.jar"))
        assertFalse(FileTypes.isBinary("logo.svg"))
        assertFalse(FileTypes.isBinary("Main.kt"))
    }
}

class ProtectedFilesTest {
    @Test
    fun configuration_files_are_important_anywhere() {
        for (p in listOf("build.gradle", "app/build.gradle.kts", "settings.gradle.kts", "gradle.properties",
            "app/src/main/AndroidManifest.xml", "app/proguard-rules.pro", "gradle/libs.versions.toml")) {
            assertTrue(p, ProtectedFiles.isProtected(p))
            assertNotNull(p, ProtectedFiles.check(p).reason)
        }
    }

    @Test
    fun some_files_are_important_only_in_the_root() {
        assertTrue(ProtectedFiles.isProtected("package.json"))
        assertFalse(ProtectedFiles.isProtected("sub/package.json"))
        assertTrue(ProtectedFiles.isProtected(".github/workflows/build.yml"))
        assertFalse(ProtectedFiles.isProtected("docs/build.yml"))
    }

    @Test
    fun ordinary_source_files_are_not_protected() {
        for (p in listOf("index.html", "style.css", "src/Main.kt", "README.md")) assertFalse(p, ProtectedFiles.isProtected(p))
        assertNull(ProtectedFiles.check("src/Main.kt").reason)
    }

    @Test
    fun names_are_matched_without_regard_to_case() {
        assertTrue(ProtectedFiles.isProtected("app/src/main/androidmanifest.xml"))
        assertTrue(ProtectedFiles.isProtected("Build.Gradle.kts"))
    }

    @Test
    fun protectedIn_lists_only_the_important_ones_sorted() {
        assertEquals(listOf("app/build.gradle", "build.gradle"), ProtectedFiles.protectedIn(listOf("build.gradle", "a.kt", "app/build.gradle")))
    }
}

class PathOpsTest {
    @Test
    fun normalizeInput_cleans_what_users_type() {
        assertEquals("a/b.kt", PathOps.normalizeInput("  ./a//b.kt "))
        assertEquals("a/b.kt", PathOps.normalizeInput("a\\b.kt"))
        assertEquals("x.kt", PathOps.normalizeInput("/x.kt"))
        assertEquals("../x.kt", PathOps.normalizeInput("../x.kt"))
    }

    @Test
    fun traversal_and_bad_names_are_rejected() {
        assertNotNull(PathOps.validateFile("../x.kt"))
        assertNotNull(PathOps.validateFile("a/../../x.kt"))
        assertNotNull(PathOps.validateFile("bad:name.kt"))
        assertNotNull(PathOps.validateFile("a.almus-tmp"))
        assertNull(PathOps.validateFile("src/Ok File-1.kt"))
        assertNotNull(PathOps.validateFolder(".."))
        assertNull(PathOps.validateFolder("src/utils/"))
    }

    @Test
    fun parent_join_inside_and_ancestors() {
        assertEquals("a/b", PathOps.parent("a/b/c.kt"))
        assertEquals("", PathOps.parent("c.kt"))
        assertEquals("a/b/c.kt", PathOps.join("a/b", "c.kt"))
        assertEquals("c.kt", PathOps.join("", "c.kt"))
        assertTrue(PathOps.isInside("a", "a/b.kt"))
        assertFalse(PathOps.isInside("a", "ab/b.kt"))
        assertFalse(PathOps.isInside("a", "a"))
        assertEquals(listOf("a", "a/b"), PathOps.ancestors("a/b/c.kt"))
        assertEquals(emptyList<String>(), PathOps.ancestors("c.kt"))
        assertEquals("x/y/z.kt", PathOps.moveUnder("a/b", "x/y", "a/b/z.kt"))
        assertEquals("x/y/q/z.kt", PathOps.moveUnder("a/b", "x/y", "a/b/q/z.kt"))
        assertNull(PathOps.moveUnder("a/b", "x/y", "a/c/z.kt"))
    }

    @Test
    fun duplicateName_adds_copy_and_counts_up() {
        assertEquals("src/Foo copy.kt", PathOps.duplicateName("src/Foo.kt", setOf("src/Foo.kt")))
        assertEquals("src/Foo copy 2.kt", PathOps.duplicateName("src/Foo.kt", setOf("src/Foo.kt", "src/Foo copy.kt")))
        assertEquals("src/Foo copy 3.kt", PathOps.duplicateName("src/Foo.kt", setOf("src/Foo copy.kt", "src/Foo copy 2.kt")))
        assertEquals("README copy", PathOps.duplicateName("README", setOf("README")))
        assertEquals(".env copy", PathOps.duplicateName(".env", setOf(".env")))
        assertEquals("app/build copy.gradle.kts", PathOps.duplicateName("app/build.gradle.kts", emptySet()))
    }

    @Test
    fun duplicateName_stays_within_name_limits() {
        val long = "a".repeat(78) + ".kt"
        val copy = PathOps.duplicateName(long, setOf(long))
        assertNotNull(copy)
        assertTrue(copy!!.length <= 80)
        assertNull(PathOps.validateFile(copy))
    }
}

class FileSearchTest {
    private val files = listOf(
        f("app/src/MainActivity.kt"), f("app/src/Main.kt"), f("res/layout/activity_main.xml"),
        f("README.md"), f("docs/main-notes.md"), f("build.gradle.kts"),
    )

    @Test
    fun blank_query_returns_everything_sorted() {
        assertEquals(files.map { it.path }.sortedBy { it.lowercase() }, FileSearch.search(files, "  ").map { it.path })
    }

    @Test
    fun better_name_matches_rank_first() {
        val result = FileSearch.search(files, "main").map { it.path }
        // Name prefix beats "contains"; ties are ordered by path.
        assertEquals(
            listOf("app/src/Main.kt", "app/src/MainActivity.kt", "docs/main-notes.md", "res/layout/activity_main.xml"),
            result,
        )
    }

    @Test
    fun every_word_must_match() {
        assertEquals(listOf("app/src/Main.kt", "app/src/MainActivity.kt").toSet(), FileSearch.search(files, "main .kt").map { it.path }.toSet())
        assertTrue(FileSearch.search(files, "main zzz").isEmpty())
    }

    @Test
    fun extension_queries_and_case_and_path_and_subsequence() {
        assertEquals(setOf("res/layout/activity_main.xml"), FileSearch.search(files, "*.xml").map { it.path }.toSet())
        assertEquals(setOf("README.md", "docs/main-notes.md"), FileSearch.search(files, ".MD").map { it.path }.toSet())
        assertTrue(FileSearch.search(files, "LAYOUT").any { it.path == "res/layout/activity_main.xml" })
        assertTrue(FileSearch.search(files, "mnact").any { it.path == "app/src/MainActivity.kt" })
        assertEquals(-1, FileSearch.score("a.kt", "xyz"))
    }

    @Test
    fun limit_is_applied() {
        assertEquals(2, FileSearch.search(files, "", limit = 2).size)
    }
}

class ExplorerTreeTest {
    private val files = listOf(
        f("b.txt", 10), f("src/z.kt", 300), f("src/a.kt", 50), f("A.md", 5), f("res/x.xml", 40), f("src/deep/q.kt", 7),
        f("build.gradle.kts", 20),
    )

    @Test
    fun folders_first_then_files_and_depths() {
        val rows = ExplorerTree.rows(files, emptySet())
        assertEquals(
            listOf("res", "res/x.xml", "src", "src/deep", "src/deep/q.kt", "src/a.kt", "src/z.kt", "A.md", "b.txt", "build.gradle.kts"),
            rows.map { it.path },
        )
        assertEquals(listOf(0, 1, 0, 1, 2, 1, 1, 0, 0, 0), rows.map { it.depth })
    }

    @Test
    fun folder_rows_carry_totals_and_important_flag() {
        val rows = ExplorerTree.rows(files, emptySet())
        val src = rows.filterIsInstance<ExplorerRow.Folder>().first { it.path == "src" }
        assertEquals(3, src.fileCount)
        assertEquals(357L, src.totalBytes)
        assertFalse(src.containsImportant)
        val withManifest = ExplorerTree.rows(files + f("app/src/main/AndroidManifest.xml"), emptySet())
        assertTrue(withManifest.filterIsInstance<ExplorerRow.Folder>().first { it.path == "app" }.containsImportant)
    }

    @Test
    fun file_rows_carry_kind_size_and_important() {
        val row = ExplorerTree.rows(files, emptySet()).filterIsInstance<ExplorerRow.File>().first { it.path == "build.gradle.kts" }
        assertEquals(FileKind.GRADLE, row.kind)
        assertEquals(20L, row.sizeBytes)
        assertTrue(row.important)
    }

    @Test
    fun collapsed_folders_hide_their_content() {
        val rows = ExplorerTree.rows(files, setOf("src"))
        assertFalse(rows.any { it.path == "src/a.kt" || it.path == "src/deep" })
        val src = rows.filterIsInstance<ExplorerRow.Folder>().first { it.path == "src" }
        assertFalse(src.expanded)
        assertEquals(3, src.fileCount)
    }

    @Test
    fun sorting_by_size_and_type() {
        val bySize = ExplorerTree.rows(files, emptySet(), sort = ExplorerSort.SIZE)
        assertEquals(listOf("src", "res"), bySize.filterIsInstance<ExplorerRow.Folder>().filter { it.depth == 0 }.map { it.path })
        assertEquals(listOf("src/z.kt", "src/a.kt"), bySize.filterIsInstance<ExplorerRow.File>().filter { it.path.startsWith("src/") && it.depth == 1 }.map { it.path })
        val byType = ExplorerTree.rows(files, emptySet(), sort = ExplorerSort.TYPE).filterIsInstance<ExplorerRow.File>().filter { it.depth == 0 }
        // Labels sort as Gradle, Markdown, Text.
        assertEquals(listOf("build.gradle.kts", "A.md", "b.txt"), byType.map { it.path })
    }

    @Test
    fun search_shows_matches_with_their_folders_even_if_collapsed() {
        val rows = ExplorerTree.rows(files, setOf("src"), query = "q.kt")
        assertEquals(listOf("src", "src/deep", "src/deep/q.kt"), rows.map { it.path })
        assertTrue(rows.filterIsInstance<ExplorerRow.Folder>().all { it.expanded })
    }

    @Test
    fun empty_and_no_match() {
        assertTrue(ExplorerTree.rows(emptyList(), emptySet()).isEmpty())
        assertTrue(ExplorerTree.rows(files, emptySet(), query = "nothingmatches").isEmpty())
    }
}

class RecentFilesTest {
    @Test
    fun touching_moves_to_front_without_duplicates_and_is_capped() {
        var r = RecentFiles()
        for (i in 1..25) r = r.touched("f$i.kt")
        assertEquals(RecentFiles.MAX, r.paths.size)
        assertEquals("f25.kt", r.paths.first())
        r = r.touched("f10.kt")
        assertEquals("f10.kt", r.paths.first())
        assertEquals(1, r.paths.count { it == "f10.kt" })
    }

    @Test
    fun rename_remove_prune_and_folder_variants() {
        val r = RecentFiles(listOf("a/x.kt", "a/y.kt", "b.kt"))
        assertEquals(listOf("a/z.kt", "a/y.kt", "b.kt"), r.renamed("a/x.kt", "a/z.kt").paths)
        assertEquals(listOf("c/x.kt", "c/y.kt", "b.kt"), r.renamedUnder("a", "c").paths)
        assertEquals(listOf("b.kt"), r.removedUnder("a").paths)
        assertEquals(listOf("a/y.kt", "b.kt"), r.removed("a/x.kt").paths)
        assertEquals(listOf("b.kt"), r.pruned(setOf("b.kt", "q.kt")).paths)
    }

    @Test
    fun key_value_store_round_trips_per_project() {
        val kv = InMemoryKeyValueStore()
        val store = KeyValueRecentFilesStore(kv)
        assertTrue(store.load("p1").paths.isEmpty())
        store.save("p1", RecentFiles(listOf("a.kt", "src/b.kt")))
        store.save("p2", RecentFiles(listOf("other.kt")))
        assertEquals(listOf("a.kt", "src/b.kt"), KeyValueRecentFilesStore(kv).load("p1").paths)
        assertEquals(listOf("other.kt"), store.load("p2").paths)
        store.save("p1", RecentFiles())
        assertTrue(store.load("p1").paths.isEmpty())
        assertTrue(kv.keys().none { it.endsWith("p1") })
    }
}

class ProjectStructureTest {
    private val files = listOf(f("README.md", 10), f("app/src/A.kt", 500), f("app/src/B.kt", 200), f("res/x.xml", 40), f("app/build.gradle.kts", 30))

    @Test
    fun counts_sizes_and_depth() {
        val s = ProjectStructureAnalyzer.analyze(files)
        assertEquals(5, s.fileCount)
        assertEquals(780L, s.totalBytes)
        assertEquals(2, s.maxDepth)
        assertEquals(3, s.folderCount) // app, app/src, res
    }

    @Test
    fun groups_by_kind_most_files_first() {
        val s = ProjectStructureAnalyzer.analyze(files)
        assertEquals(FileKind.KOTLIN, s.byKind.first().kind)
        assertEquals(2, s.byKind.first().fileCount)
        assertEquals(700L, s.byKind.first().totalBytes)
        assertEquals(setOf(FileKind.KOTLIN, FileKind.XML, FileKind.MARKDOWN, FileKind.GRADLE), s.byKind.map { it.kind }.toSet())
    }

    @Test
    fun top_folders_and_largest_files() {
        val s = ProjectStructureAnalyzer.analyze(files, largestCount = 2)
        assertEquals(listOf("app", "res", ProjectStructureAnalyzer.ROOT), s.topFolders.map { it.path })
        assertEquals(730L, s.topFolders.first().totalBytes)
        assertEquals(listOf("app/src/A.kt", "app/src/B.kt"), s.largest.map { it.path })
    }

    @Test
    fun empty_project() {
        val s = ProjectStructureAnalyzer.analyze(emptyList())
        assertEquals(0, s.fileCount)
        assertEquals(0, s.maxDepth)
        assertTrue(s.byKind.isEmpty())
    }

    @Test
    fun ascii_tree_draws_folders_then_files() {
        val tree = ProjectStructureAnalyzer.asciiTree("Demo", listOf(f("a/x.kt"), f("a/y.kt"), f("z.md")))
        assertEquals(
            listOf("Demo/", "├── a/", "│   ├── x.kt", "│   └── y.kt", "└── z.md").joinToString("\n"),
            tree,
        )
    }

    @Test
    fun ascii_tree_is_cut_after_max_lines() {
        val many = (1..50).map { f("f$it.kt") }
        val tree = ProjectStructureAnalyzer.asciiTree("P", many, maxLines = 10)
        assertEquals(11, tree.lines().size)
        assertTrue(tree.lines().last().startsWith("… "))
    }
}

class MarkdownAndPreviewTest {
    @Test
    fun markdown_colors_headings_fences_quotes_lists_code_and_links() {
        val text = "# Title\nText with `code` and [link](http://x)\n> quote\n- item\n1. first\n```\nval a = 1\n```\nplain"
        val tokens = MarkdownHighlighter.tokenize(text)
        fun covered(type: TokenType) = tokens.filter { it.type == type }.map { text.substring(it.start, it.end) }
        assertEquals(listOf("# Title"), covered(TokenType.KEYWORD))
        assertTrue("`code`" in covered(TokenType.STRING))
        assertTrue("val a = 1" in covered(TokenType.STRING))
        assertTrue("[link](http://x)" in covered(TokenType.TAG))
        assertEquals(listOf("> quote"), covered(TokenType.COMMENT))
        assertEquals(listOf("-", "1."), covered(TokenType.ANNOTATION))
    }

    @Test
    fun markdown_tokens_are_ordered_and_never_overlap() {
        val text = "# H\n- a `b` [c](d)\n```\nx\n```\n> q\n1) z `y`"
        val tokens = MarkdownHighlighter.tokenize(text)
        for (i in 1 until tokens.size) assertTrue(tokens[i - 1].end <= tokens[i].start)
        assertTrue(tokens.all { it.start in 0 until it.end && it.end <= text.length })
    }

    @Test
    fun a_hash_without_a_space_is_not_a_heading() {
        assertTrue(MarkdownHighlighter.tokenize("#hashtag").isEmpty())
        assertTrue(MarkdownHighlighter.tokenize("").isEmpty())
    }

    @Test
    fun preview_of_a_small_file_is_complete_and_colored() {
        val p = FilePreviewBuilder.build("Main.kt", "fun main() {\n    println(\"hi\")\n}\n", 31)
        assertTrue(p.hasText)
        assertFalse(p.truncated)
        assertEquals(FileKind.KOTLIN, p.kind)
        assertEquals(4, p.totalLines)
        assertTrue(p.tokens.any { it.type == TokenType.KEYWORD })
        assertTrue(p.tokens.any { it.type == TokenType.STRING })
    }

    @Test
    fun preview_of_a_long_file_is_cut_at_a_line_boundary() {
        val text = (1..5000).joinToString("\n") { "line $it" }
        val p = FilePreviewBuilder.build("a.txt", text, text.length.toLong())
        assertTrue(p.truncated)
        assertEquals(FilePreviewBuilder.MAX_PREVIEW_LINES, p.shownLines)
        assertEquals(5000, p.totalLines)
        assertEquals(p.shownLines, p.text.lines().size)
    }

    @Test
    fun preview_respects_the_character_limit() {
        val text = (1..400).joinToString("\n") { "x".repeat(500) }
        val p = FilePreviewBuilder.build("a.txt", text, text.length.toLong())
        assertTrue(p.truncated)
        assertTrue(p.text.length <= FilePreviewBuilder.MAX_PREVIEW_CHARS)
    }

    @Test
    fun empty_file_and_unavailable_preview() {
        val empty = FilePreviewBuilder.build("e.txt", "", 0)
        assertEquals(0, empty.totalLines)
        assertEquals(0, empty.shownLines)
        assertFalse(empty.truncated)
        val bin = FilePreviewBuilder.unavailable("logo.png", 1234, "Binary file")
        assertFalse(bin.hasText)
        assertEquals("Binary file", bin.note)
        assertEquals(FileKind.IMAGE, bin.kind)
    }

    @Test
    fun markdown_files_use_the_markdown_highlighter() {
        val p = FilePreviewBuilder.build("README.md", "# Hi\n", 5)
        assertTrue(p.tokens.any { it.type == TokenType.KEYWORD })
    }
}
