package com.almus.appcreatorpro.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersAndValidatorTest {
    private fun <T> ok(p: Parsed<T>): T = (p as? Parsed.Ok)?.value ?: error("expected Ok but was $p")
    private fun bad(p: Parsed<*>): String = (p as? Parsed.Bad)?.reason ?: error("expected Bad but was $p")

    // ---- JSON extraction
    @Test fun `json is found plain, fenced or surrounded by text; garbage and truncation are explained`() {
        assertTrue(AiJson.parseObject("{\"a\":1}", false) is Parsed.Ok)
        assertTrue(AiJson.parseObject("Here you go:\n```json\n{\"a\":1}\n```\nBye", false) is Parsed.Ok)
        assertTrue(AiJson.parseObject("text {\"a\":1} text", false) is Parsed.Ok)
        assertTrue(bad(AiJson.parseObject("no json here", false)).contains("JSON"))
        assertTrue(bad(AiJson.parseObject("{\"files\":[{\"path\":\"a\",\"con", true)).contains("cut off"))
        assertTrue(bad(AiJson.parseObject("{\"files\":[", false)).contains("JSON"))
    }

    // ---- plan
    private val planJson = """{"project_name":"My Notes!!","summary":"Offline notes","package_name":"com.example.notes","language":"kotlin",
        "technologies":[{"name":"Room","purpose":"storage"},{"name":"","purpose":"x"}],"dependencies":["androidx.room:room-runtime:2.6.1"],
        "architecture":"MVVM","structure":["app/src/main/java/com/example/notes/MainActivity.kt - UI"],"steps":[{"title":"DB","detail":"entities"}],"risks":["none"]}"""

    @Test fun `plan is parsed and names are cleaned`() {
        val p = ok(ProjectPlanParser.parse(planJson))
        assertEquals("My Notes", p.projectName); assertEquals("com.example.notes", p.packageName)
        assertEquals(1, p.technologies.size); assertEquals("Room", p.technologies[0].name)
        assertTrue(p.toPromptText().contains("Room: storage"))
    }

    @Test fun `plan with hostile name or package falls back to safe values`() {
        val p = ok(ProjectPlanParser.parse("""{"project_name":"../../etc","summary":"s","package_name":"Not A Package!"}"""))
        assertFalse(p.projectName.contains("/")); assertFalse(p.projectName.startsWith("."))
        assertTrue(p.packageName.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")))
        assertTrue(bad(ProjectPlanParser.parse("""{"project_name":"X"}""")).contains("summary"))
    }

    // ---- generated project
    @Test fun `generated project needs files with path and content`() {
        val g = ok(GeneratedProjectParser.parse("""{"project_name":"NotesApp","package_name":"com.example.notes","files":[{"path":"a.kt","content":"x"}],"notes":["n"]}"""))
        assertEquals("NotesApp", g.projectName); assertEquals(1, g.files.size); assertEquals(listOf("n"), g.notes)
        assertTrue(bad(GeneratedProjectParser.parse("""{"files":[]}""")).contains("no files"))
        assertTrue(bad(GeneratedProjectParser.parse("""{"project_name":"X"}""")).contains("files"))
        assertTrue(bad(GeneratedProjectParser.parse("""{"files":[{"path":"a.kt"}]}""")).contains("content"))
        assertTrue(bad(GeneratedProjectParser.parse("""{"files":["x"]}""")).contains("not an object"))
    }

    // ---- validator
    private fun gf(path: String, content: String = "x = 1") = GeneratedFile(path, content)
    private fun rejectedPaths(vararg f: GeneratedFile) = GeneratedFileValidator.validate(f.toList()).rejected.map { it.path }

    @Test fun `unsafe paths are rejected`() {
        val bad = listOf("../evil.kt", "/abs/x.kt", "a/../../x.kt", "a\\b.kt", "./a.kt", "a//b.kt", "", " a.kt", "a/.git/config", ".github/workflows/x.yml", "app/.gradle/x", "node_modules/x/y.js")
        assertEquals(bad.toSet(), rejectedPaths(*bad.map { gf(it) }.toTypedArray()).toSet())
    }

    @Test fun `binary files, wrapper files, credentials and build output are rejected`() {
        val bad = listOf("app/src/main/res/drawable/a.png", "libs/x.jar", "app/release.jks", "local.properties", "keystore.properties", "gradlew", "gradle/wrapper/gradle-wrapper.properties", "build/outputs/apk/a.apk", "app/google-services.json", ".env")
        assertEquals(bad.toSet(), rejectedPaths(*bad.map { gf(it) }.toTypedArray()).toSet())
    }

    @Test fun `content problems are rejected per file and good files survive`() {
        val files = listOf(
            gf("app/src/main/java/com/example/notes/Foo.kt", GOOD_KOTLIN),
            gf("empty.kt", "  \n"), gf("bin.kt", "a\u0000b"),
            gf("Secrets.kt", "val k = \"AIzaSyA1234567890abcdefghijklmnopqrstuvw\""),
            gf("big.kt", "a".repeat(GeneratedFileValidator.MAX_FILE_BYTES + 1)),
            gf("dup.kt"), gf("DUP.kt"),
        )
        val r = GeneratedFileValidator.validate(files)
        assertEquals(setOf("empty.kt", "bin.kt", "Secrets.kt", "big.kt", "DUP.kt"), r.rejected.map { it.path }.toSet())
        assertEquals(setOf("app/src/main/java/com/example/notes/Foo.kt", "dup.kt"), r.accepted.map { it.path }.toSet())
        assertTrue(r.hasBlockingProblems)
    }

    @Test fun `a path used as file and folder is rejected`() {
        val r = GeneratedFileValidator.validate(listOf(gf("app/x"), gf("app/x/y.kt")))
        assertEquals(listOf("app/x"), r.rejected.map { it.path })
    }

    @Test fun `too many files are refused as a whole`() {
        val r = GeneratedFileValidator.validate((1..GeneratedFileValidator.MAX_FILES + 1).map { gf("f$it.kt") })
        assertTrue(r.accepted.isEmpty()); assertTrue(r.hasBlockingProblems)
    }

    @Test fun `warnings: placeholder, package mismatch, missing manifest and gradle files`() {
        val r = GeneratedFileValidator.validate(listOf(
            gf("app/src/main/java/com/example/notes/A.kt", "package com.other\n\nclass A {\n    // ...\n}\n"),
            gf("app/src/main/java/com/example/notes/B.kt", "class B"),
            gf("app/src/main/java/com/example/notes/C.kt", "package com.example.notes\n// rest of the file\n"),
        ))
        val w = r.warnings.joinToString("\n")
        assertTrue(w.contains("declares package com.other")); assertTrue(w.contains("no package line")); assertTrue(w.contains("may be incomplete"))
        assertTrue(w.contains("AndroidManifest.xml")); assertTrue(w.contains("settings.gradle")); assertTrue(w.contains("app/build.gradle"))
    }

    @Test fun `a complete small project has no rejections and no project-level warnings`() {
        val r = GeneratedFileValidator.validate(listOf(
            gf("settings.gradle.kts", "rootProject.name = \"Notes\"\ninclude(\":app\")\n"),
            gf("build.gradle.kts", "plugins {}\n"), gf("app/build.gradle.kts", "plugins {}\n"),
            gf("app/src/main/AndroidManifest.xml", GOOD_MANIFEST),
            gf("app/src/main/java/com/example/notes/Foo.kt", GOOD_KOTLIN),
        ))
        assertTrue(r.rejected.toString(), r.rejected.isEmpty()); assertFalse(r.hasBlockingProblems)
        assertTrue(r.warnings.toString(), r.warnings.isEmpty())
    }

    // ---- edit selection / proposal / review
    @Test fun `edit selection keeps only real paths`() {
        val s = ok(EditSelectionParser.parse("""{"summary":"s","files":[{"path":"a/Login.kt","reason":"ui"},{"path":"made/up.kt"},{"path":"a/Login.kt"}]}""", setOf("a/Login.kt", "b.kt")))
        assertEquals(listOf("a/Login.kt"), s.files.map { it.path })
    }

    @Test fun `edit proposal accepts content or edits`() {
        val p = ok(EditProposalParser.parse("""{"summary":"s","changes":[{"path":"a.kt","action":"modify","content":"new"},{"path":"b.kt","action":"MODIFY","edits":[{"find":"x","replace":"y"},{"find":"z"}]},"junk"]}"""))
        assertEquals(2, p.changes.size); assertEquals("new", p.changes[0].content); assertEquals("modify", p.changes[1].action)
        assertEquals(1, p.changes[1].edits.size); assertNull(p.changes[1].content)
        assertTrue(bad(EditProposalParser.parse("""{"summary":"s"}""")).contains("changes"))
    }

    @Test fun `review is sorted by severity, capped and sanitised`() {
        val many = (1..40).joinToString(",") { """{"category":"security","severity":"low","title":"t$it"}""" }
        val r = ok(ReviewParser.parse("""{"summary":"s","findings":[{"category":"weird","severity":"nope","title":"info one"},{"category":"performance","severity":"high","path":"a.kt","line":12,"title":"hot loop","detail":"d","suggestion":"s"},{"title":""},$many]}"""))
        assertEquals(ReviewParser.MAX_FINDINGS, r.findings.size)
        assertEquals("hot loop", r.findings[0].title); assertEquals(ReviewCategory.PERFORMANCE, r.findings[0].category); assertEquals(12, r.findings[0].line)
        assertEquals(ReviewCategory.QUALITY, ReviewCategory.fromWire("weird")); assertEquals(ReviewSeverity.INFO, ReviewSeverity.fromWire("nope"))
        assertTrue(bad(ReviewParser.parse("""{"summary":"","findings":[]}""")).contains("empty"))
    }
}
