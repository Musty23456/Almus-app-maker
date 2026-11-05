package com.almus.appcreatorpro.debug

import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.template.ProjectTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticDiagnosticsTest {

    private val sample = Samples.project("android-static")
    private val result = StaticDiagnostics().analyze(sample)
    private val issues = result.issues

    private fun find(rule: String, path: String? = null, symbol: String? = null) =
        issues.filter { it.rule == rule && (path == null || it.projectPath == path) && (symbol == null || it.symbol == symbol) }

    private val main = "app/src/main/java/com/example/demo/MainActivity.kt"
    private val helper = "app/src/main/java/com/example/demo/Helper.kt"
    private val legacy = "app/src/main/java/com/example/demo/Legacy.java"

    @Test fun missingImportsAreFoundWithFullyQualifiedSuggestion() {
        val bundle = find("static.missing-import", main, "Bundle").single()
        assertEquals(9, bundle.line)
        assertTrue(bundle.suggestion!!.contains("import android.os.Bundle"))
        assertEquals(Severity.ERROR, bundle.severity)
        assertTrue(find("static.missing-import", main, "Log").isNotEmpty())
        // TextView and OkHttpClient are imported; AppCompatActivity too.
        assertTrue(find("static.missing-import", main, "TextView").isEmpty())
        assertTrue(find("static.missing-import", main, "OkHttpClient").isEmpty())
    }

    @Test fun unusedImportAndVariablesAreWarnings() {
        assertEquals(3, find("static.unused-import", main).single().line)
        assertEquals("unused", find("static.unused-variable", main, "unused").single().symbol)
        assertEquals(Severity.WARNING, find("static.unused-variable", main, "unused").single().severity)
        assertEquals("spare", find("static.unused-variable", legacy).single().symbol)
        assertTrue(find("static.unused-import", legacy).isNotEmpty())
    }

    @Test fun variablesThatAreUsedAreNotReported() {
        assertTrue(find("static.unused-variable", main, "title").isEmpty())
        assertTrue(find("static.unused-variable", main, "name").isEmpty())
        assertTrue(find("static.unused-variable", helper, "limit").isEmpty())
    }

    @Test fun undefinedNameInPlainKotlinFile() {
        val n = find("static.undefined-name", helper, "summ").single()
        assertEquals(9, n.line)
        assertEquals(Confidence.LOW, n.confidence)
    }

    @Test fun inheritedMembersOfActivityAreNotFlagged() {
        assertTrue(find("static.undefined-name", main).isEmpty())
    }

    @Test fun valReassignmentIsAnError() {
        val v = find("static.val-reassign", helper, "count").single()
        assertEquals(14, v.line)
        assertEquals(Severity.ERROR, v.severity)
    }

    @Test fun commonKotlinAndJavaMistakes() {
        assertEquals(17, find("static.not-null-assertion", main).single().line)
        assertEquals(20, find("static.empty-catch", main).single().line)
        assertEquals(19, find("static.sleep-ui", main).single().line)
        assertEquals(7, find("static.string-reference-compare", legacy).single().line)
        assertEquals(11, find("static.missing-semicolon", legacy).single().line)
        assertEquals(18, find("static.unreachable-code", legacy).single().line)
    }

    @Test fun missingResourcesInXmlAndCode() {
        val xml = find("static.resource-missing", "app/src/main/res/layout/activity_main.xml")
        assertEquals(setOf("string/missing_title", "drawable/ic_missing"), xml.map { it.symbol }.toSet())
        val code = find("static.resource-missing", main).single()
        assertEquals("string/missing_title", code.symbol)
        assertEquals(13, code.line)
        // @+id/title and R.id.title are defined by the layout.
        assertTrue(find("static.resource-missing", main).none { it.symbol == "id/title" })
        assertTrue(issues.none { it.symbol == "string/app_name" })
    }

    @Test fun malformedXmlIsReportedWithLine() {
        val x = find("static.xml-malformed", "app/src/main/res/values/broken.xml").single()
        assertEquals(2, x.line)
        assertTrue(x.message.contains("&amp;"))
    }

    @Test fun manifestProblems() {
        val manifest = "app/src/main/AndroidManifest.xml"
        assertEquals(6, find("static.manifest-exported", manifest).single().line)
        assertEquals("GhostActivity", find("static.manifest-class-missing", manifest).single().symbol)
        assertTrue(find("static.manifest-internet", manifest).isNotEmpty())
        // MainActivity exists, so it is not reported as missing.
        assertTrue(find("static.manifest-class-missing", manifest).none { it.symbol == "MainActivity" })
    }

    @Test fun gradleConfigurationProblems() {
        val g = "app/build.gradle.kts"
        assertTrue(find("static.gradle-namespace", g).isNotEmpty())
        assertEquals(12, find("static.gradle-target-gt-compile", g).single().line)
        assertEquals("com.squareup.okhttp3:okhttp", find("static.gradle-dependency-no-version", g).single().message.substringAfter('`').substringBefore('`'))
    }

    @Test fun everyIssueHasLocationOrIsProjectLevelAndNavigable() {
        for (i in issues) {
            assertEquals(IssueSource.STATIC_ANALYSIS, i.source)
            assertNotNull(i.suggestion)
            if (i.line != null) assertTrue(i.rule, i.canNavigate)
        }
    }

    @Test fun errorsSortBeforeWarnings() {
        assertEquals(issues.map { it.severity.rank }.sorted(), issues.map { it.severity.rank })
    }

    // ---------------------------------------------------------------- fixes really fix

    @Test fun everyOfferedFixAppliesAndRemovesItsIssue() {
        var checked = 0
        for (finding in result.findings) {
            for (fix in finding.fixes) {
                val text = sample[fix.path] ?: continue
                assertEquals(TextEdits.hash(text), fix.baseHash)
                val applied = TextEdits.apply(text, fix.edits)
                assertTrue("fix ${fix.title}", applied is TextEdits.Result.Ok)
                val newText = (applied as TextEdits.Result.Ok).text
                val again = StaticDiagnostics().analyze(sample + (fix.path to newText))
                if (finding.issue.line != null && fix.issueId == finding.issue.id) {
                    assertTrue(
                        "issue still present after ${fix.title}",
                        again.issues.none { it.rule == finding.issue.rule && it.projectPath == fix.path && it.symbol == finding.issue.symbol && it.message == finding.issue.message },
                    )
                }
                checked++
            }
        }
        assertTrue(checked >= 8)
    }

    @Test fun addAllMissingImportsInOneFix() {
        val fix = result.fixes.first { it.title.startsWith("Add all") && it.path == main }
        val text = (TextEdits.apply(sample.getValue(main), fix.edits) as TextEdits.Result.Ok).text
        assertTrue(text.contains("import android.os.Bundle"))
        assertTrue(text.contains("import android.util.Log"))
        val after = StaticDiagnostics().analyze(sample + (main to text))
        assertTrue(after.issues.none { it.rule == "static.missing-import" && it.projectPath == main })
    }

    @Test fun exportedFixKeepsManifestWellFormed() {
        val fix = result.fixes.first { it.title.startsWith("Add android:exported") }
        val text = (TextEdits.apply(sample.getValue(fix.path), fix.edits) as TextEdits.Result.Ok).text
        assertTrue(text.contains("android:exported=\"true\""))
        assertTrue(StaticDiagnostics().analyze(sample + (fix.path to text)).issues.none { it.rule == "static.xml-malformed" && it.projectPath == fix.path })
    }

    // ---------------------------------------------------------------- no false alarms on healthy code

    @Test fun freshlyGeneratedProjectsHaveNoErrors() {
        for (type in listOf(ProjectType.ANDROID_KOTLIN, ProjectType.ANDROID_JAVA)) {
            val files = ProjectTemplates.filesFor("Demo App", "com.example.demoapp", type)
            val r = StaticDiagnostics().analyze(files)
            val errors = r.issues.filter { it.severity == Severity.ERROR }
            assertTrue("$type: " + errors.joinToString { "${it.rule}@${it.projectPath}:${it.line}" }, errors.isEmpty())
        }
    }

    @Test fun webProjectIsNotAnalysedAsAndroid() {
        val files = ProjectTemplates.filesFor("Site", "com.example.site", ProjectType.WEB)
        assertTrue(StaticDiagnostics().analyze(files).issues.none { it.severity == Severity.ERROR })
    }

    @Test fun healthyKotlinFileProducesNothing() {
        val code = """
            package com.example

            import kotlin.math.max

            data class Point(val x: Int, val y: Int)

            class Counter {
                private var count = 0
                fun next(): Int { count += 1; return count }
                fun biggest(a: Int, b: Int): Int = max(a, b)
            }

            fun describe(p: Point): String {
                val label = "(${'$'}{p.x}, ${'$'}{p.y})"
                return label
            }
        """.trimIndent()
        val r = StaticDiagnostics().analyze(mapOf("src/Healthy.kt" to code))
        assertTrue(r.issues.joinToString { it.rule + ":" + it.message }, r.issues.isEmpty())
    }

    @Test fun mutableListPlusAssignOnValIsNotAReassignment() {
        val code = "fun f(): List<Int> {\n    val items = mutableListOf<Int>()\n    items += 1\n    return items\n}\n"
        val r = StaticDiagnostics().analyze(mapOf("A.kt" to code))
        assertTrue(r.issues.none { it.rule == "static.val-reassign" })
    }

    @Test fun stringTemplateCountsAsUse() {
        val code = "fun f(): String {\n    val name = \"x\"\n    return \"hi \$name\"\n}\n"
        assertTrue(StaticDiagnostics().analyze(mapOf("A.kt" to code)).issues.isEmpty())
    }

    @Test fun unbalancedBracketsAreReportedOnceAndStopOtherChecks() {
        val code = "package a\n\nfun f(x: Int) {\n    if (x > 1) {\n        println(x)\n    \n}\n"
        val r = StaticDiagnostics().analyze(mapOf("A.kt" to code))
        assertEquals(1, r.issues.size)
        assertEquals("static.brackets", r.issues[0].rule)
        assertEquals(3, r.issues[0].line)
    }

    @Test fun bracketsInStringsAndCommentsAreIgnored() {
        val code = "fun f() {\n    val s = \"( [ {\"  // }}}\n    /* ) ] */\n    println(s)\n}\n"
        assertTrue(StaticDiagnostics().analyze(mapOf("A.kt" to code)).issues.isEmpty())
    }

    @Test fun projectClassWithoutImportIsFlaggedWhenInAnotherPackage() {
        val files = mapOf(
            "a/Thing.kt" to "package com.a\n\nclass Thing\n",
            "b/User.kt" to "package com.b\n\nfun use(): Any = Thing()\n",
            "a/Same.kt" to "package com.a\n\nfun same(): Any = Thing()\n",
        )
        val r = StaticDiagnostics().analyze(files)
        assertEquals(listOf("b/User.kt"), r.issues.filter { it.rule == "static.missing-import" }.map { it.projectPath })
        val fix = r.fixes.single { it.path == "b/User.kt" }
        val text = (TextEdits.apply(files.getValue("b/User.kt"), fix.edits) as TextEdits.Result.Ok).text
        assertTrue(text.contains("import com.a.Thing"))
    }

    @Test fun emptyProjectIsFine() {
        assertEquals(0, StaticDiagnostics().analyze(emptyMap()).findings.size)
    }
}

class SourceTextTest {
    @Test fun maskBlanksCommentsAndStringsButKeepsLayout() {
        val code = "val a = \"x{\" // c(\nval b = 'q' /* ) */\n"
        val m = SourceText.mask(code, kotlin = true)
        assertEquals(code.length, m.length)
        assertEquals(code.count { it == '\n' }, m.count { it == '\n' })
        assertFalse(m.contains("c("))
        assertFalse(m.contains("x{"))
    }

    @Test fun kotlinTemplatesStayVisibleInMask() {
        val m = SourceText.mask("\"hello \$name and \${a.b}\"", kotlin = true)
        assertTrue(m.contains("\$name"))
        assertTrue(m.contains("\${a.b}"))
    }

    @Test fun selectionForLine() {
        val text = "one\ntwo\nthree"
        assertEquals(4..7, SourceText.selectionFor(text, 2, null))
        assertEquals(6..6, SourceText.selectionFor(text, 2, 3))
        assertEquals(8..13, SourceText.selectionFor(text, 99, null)) // clamped to the last line
        assertEquals(0..0, SourceText.selectionFor(text, null, null))
    }

    @Test fun lineIndexRoundTrip() {
        val idx = SourceText.LineIndex("ab\ncd\n\nef")
        assertEquals(4, idx.lineCount)
        assertEquals(1, idx.lineOf(0)); assertEquals(2, idx.lineOf(3)); assertEquals(3, idx.lineOf(6)); assertEquals(4, idx.lineOf(8))
        assertEquals(3, idx.startOf(2))
    }
}

class ProjectPathResolverTest {
    private val files = listOf(
        "app/src/main/java/com/x/MainActivity.kt", "app/src/main/java/com/y/MainActivity.kt", "app/src/main/res/values/strings.xml",
        "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
    )
    private val r = ProjectPathResolver(files)

    @Test fun ciAbsolutePathMapsToProjectFile() {
        assertEquals("app/src/main/java/com/x/MainActivity.kt", r.resolve("/home/runner/work/r/r/app/src/main/java/com/x/MainActivity.kt"))
        assertEquals("app/src/main/java/com/x/MainActivity.kt", r.resolve("file:///home/runner/work/r/r/app/src/main/java/com/x/MainActivity.kt"))
    }

    @Test fun longestSuffixWins() {
        assertEquals("app/build.gradle.kts", r.resolve("/work/r/app/build.gradle.kts"))
        assertEquals("build.gradle.kts", r.resolve("/work/r/build.gradle.kts"))
    }

    @Test fun ambiguousFileNameIsNotGuessed() {
        assertEquals(null, r.resolve("/somewhere/MainActivity.kt"))
    }

    @Test fun uniqueFileNameAndRelativeTail() {
        assertEquals("app/src/main/res/values/strings.xml", r.resolve("/elsewhere/strings.xml"))
        assertEquals("app/src/main/res/values/strings.xml", r.resolve("res/values/strings.xml"))
    }

    @Test fun windowsSeparatorsAndEmpty() {
        assertEquals("app/build.gradle.kts", r.resolve("C:\\work\\app\\build.gradle.kts"))
        assertEquals(null, r.resolve(null)); assertEquals(null, r.resolve(" "))
    }
}
