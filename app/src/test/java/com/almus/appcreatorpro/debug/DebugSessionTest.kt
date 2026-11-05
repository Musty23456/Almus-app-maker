package com.almus.appcreatorpro.debug

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** End to end on the sample projects that contain intentional errors: detect, navigate, fix, undo. */
class DebugSessionTest {
    private fun session(project: String, log: String?, ai: AiDebugProvider = NoAiProvider): Pair<DebugSession, FakeFiles> {
        val files = FakeFiles(Samples.project(project))
        val s = DebugSession(
            "Sample", if (log == null) null else "b1", files, { files.data.keys.sorted() },
            { RawBuildLog(log?.let { Samples.text(it) }, null) }, ai,
        )
        return s to files
    }

    @Test fun kotlinBuildLogErrorsAreDetectedAndNavigable() = runBlocking {
        val (s, files) = session("kotlin-errors", "kotlin-errors/build.log")
        s.loadBuildLog()
        val st = s.state.value
        assertTrue(st.logLoaded); assertEquals(true, st.buildFailed.let { it ?: true })
        val errors = st.buildIssues.filter { it.severity == Severity.ERROR }
        assertTrue("expected several errors, got ${errors.size}", errors.size >= 4)
        for (e in errors.filter { it.projectPath != null }) {
            val t = s.targetFor(e)!!
            assertTrue("${t.path} must exist", files.data.containsKey(t.path))
            assertNotNull(t.line)
            val text = files.data[t.path]!!
            val sel = SourceText.selectionFor(text, t.line, t.column)
            assertTrue(sel.first in 0..text.length)
            // The caret/selection must sit on the reported line.
            assertEquals(t.line, SourceText.LineIndex(text).lineOf(sel.first))
        }
        assertTrue(errors.any { it.category == ErrorCategory.KOTLIN_COMPILATION && it.projectPath!!.endsWith("Calculator.kt") && it.line == 10 })
    }

    @Test fun javaBuildLogErrorsNavigate() = runBlocking {
        val (s, files) = session("java-errors", "java-errors/build.log")
        s.loadBuildLog()
        val java = s.state.value.buildIssues.filter { it.category == ErrorCategory.JAVA_COMPILATION }
        assertTrue(java.isNotEmpty())
        assertTrue(java.all { it.projectPath != null && files.data.containsKey(it.projectPath!!) })
        assertTrue(java.any { it.line == 7 })
    }

    @Test fun logViewerFilterSearchCopyExport() = runBlocking {
        val (s, _) = session("kotlin-errors", "kotlin-errors/build.log")
        s.loadBuildLog()
        val all = s.state.value.visibleLog.size
        s.setQuery("labl")
        assertTrue(s.state.value.visibleLog.isNotEmpty() && s.state.value.visibleLog.size < all)
        assertTrue(s.copyText().contains("labl"))
        s.setQuery("")
        s.toggleLevel(LogLevel.INFO)
        assertTrue(s.state.value.visibleLog.all { it.level != LogLevel.INFO })
        val json = s.exportText(LogExportFormat.JSON)
        assertTrue(json.trimStart().startsWith("{"))
        assertTrue(s.exportText(LogExportFormat.TEXT).isNotBlank())
    }

    @Test fun missingLogGivesANoteNotACrash() = runBlocking {
        val files = FakeFiles(emptyMap())
        val s = DebugSession("x", "b1", files, { emptyList() }, { RawBuildLog(null, "GitHub has not listed a job yet.") })
        s.loadBuildLog()
        assertEquals("GitHub has not listed a job yet.", s.state.value.logNote)
        assertTrue(s.state.value.issues.isEmpty())
        val thrower = DebugSession("x", "b1", files, { emptyList() }, { throw java.io.IOException("offline") })
        thrower.loadBuildLog()
        assertTrue(thrower.state.value.logNote!!.contains("offline"))
    }

    @Test fun staticChecksFindFixAndUndoOnSampleAndroidProject() = runBlocking {
        val (s, files) = session("android-static", null)
        s.runStaticChecks()
        val before = s.state.value
        assertTrue(before.checksRan && before.filesChecked > 0)
        assertTrue(before.staticIssues.any { it.severity == Severity.ERROR })
        assertTrue(before.staticIssues.all { it.source == IssueSource.STATIC_ANALYSIS })
        val fixable = before.staticIssues.firstNotNullOf { i -> s.fixesFor(i).firstOrNull()?.let { i to it } }
        val (issue, entry) = fixable
        val original = files.data[entry.proposal.path]!!
        assertNotNull(s.fixes.preview(entry.proposal.id))
        assertTrue(s.applyFix(entry.proposal.id) is FixResult.Applied)
        assertNotEquals(original, files.data[entry.proposal.path])
        assertTrue("issue disappears after the fix", s.state.value.staticIssues.none { it.id == issue.id })
        assertTrue(s.undoFix() is UndoResult.Undone)
        assertEquals(original, files.data[entry.proposal.path])
        assertTrue(s.state.value.staticIssues.any { it.id == issue.id })
    }

    @Test fun malformedXmlSampleIsReportedWithLocation() = runBlocking {
        val (s, _) = session("android-static", null)
        s.runStaticChecks()
        assertTrue(s.state.value.staticIssues.any { it.projectPath?.endsWith("broken.xml") == true && it.line != null })
    }

    @Test fun issueFilterSeparatesSources() = runBlocking {
        val (s, _) = session("kotlin-errors", "kotlin-errors/build.log")
        s.loadBuildLog(); s.runStaticChecks()
        s.setIssueFilter(IssueFilter.BUILD)
        assertTrue(s.state.value.issues.all { it.source == IssueSource.BUILD_LOG })
        s.setIssueFilter(IssueFilter.STATIC)
        assertTrue(s.state.value.issues.all { it.source == IssueSource.STATIC_ANALYSIS })
    }

    @Test fun noAiMeansNoAnswerAndNoFixes() = runBlocking {
        val (s, _) = session("kotlin-errors", "kotlin-errors/build.log")
        s.loadBuildLog()
        assertTrue(s.aiAvailability() is AiAvailability.NotReady)
        val req = s.buildAiRequest(AiTask.EXPLAIN, includeCode = false)
        assertFalse(req.containsSourceCode)
        assertTrue(s.askAi(req) is AiOutcome.Unavailable)
        assertTrue(s.fixes.entries.value.isEmpty())
    }

    @Test fun fakeAiProviderPatchBecomesAReviewableFix() = runBlocking {
        val fake = object : AiDebugProvider {
            override val id = "fake"; override val displayName = "Fake"
            override suspend fun availability() = AiAvailability.Ready
            override suspend fun analyze(request: AiDebugRequest): AiResult = AiResult.Success(
                AiDebugResponse("s", "c", "e", listOf(
                    AiPatch("app/src/main/java/com/example/broken/Calculator.kt", "Fix name", "typo", listOf(AiPatchEdit("labl + value", "label + value"))),
                    AiPatch("nope.kt", "bad", "bad", listOf(AiPatchEdit("a", "b"))),
                )),
            )
        }
        val (s, files) = session("kotlin-errors", "kotlin-errors/build.log", fake)
        s.loadBuildLog()
        val out = s.askAi(s.buildAiRequest(AiTask.GENERATE_PATCH, includeCode = true)) as AiOutcome.Answer
        assertEquals(1, out.rejectedPatches.size)
        val fix = s.fixes.entries.value.single()
        assertEquals(FixOrigin.AI, fix.proposal.origin); assertEquals(FixStatus.PENDING, fix.status)
        assertFalse(files.data[fix.proposal.path]!!.contains("label + value")) // nothing applied by itself
        assertTrue(s.applyFix(fix.proposal.id) is FixResult.Applied)
        assertTrue(files.data[fix.proposal.path]!!.contains("label + value"))
    }
}

class RouteArgsTest {
    @Test fun roundTripsAwkwardPaths() {
        for (p in listOf("app/src/main/java/com/x/Main.kt", "my dir/File Name.kt", "a?b&c=d/e#f.kt", "ünï/日本.kt", "100%/x+y.kt", "emoji/😀.kt")) {
            val e = RouteArgs.encode(p)
            assertTrue("no raw specials in $e", e.none { it == ' ' || it == '?' || it == '&' || it == '#' || it == '+' || it == '=' })
            assertEquals(p, RouteArgs.decode(e))
        }
        assertEquals("a%20b", RouteArgs.encode("a b"))
        assertEquals("plain-id_1.2~x", RouteArgs.encode("plain-id_1.2~x"))
    }
}
