package com.almus.appcreatorpro.debug

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeFiles(initial: Map<String, String>) : ProjectFileAccess {
    val data = HashMap(initial)
    var failWrites = false
    override suspend fun read(path: String): String = data[path] ?: throw java.io.FileNotFoundException(path)
    override suspend fun write(path: String, content: String) {
        if (failWrites) throw java.io.IOException("disk full")
        data[path] = content
    }
}

private const val SRC = "line1\nline2\nline3\nline4\n"

private fun proposal(id: String, text: String = SRC, path: String = "A.kt", edit: CodeEdit = CodeEdit.replace(2, "LINE2")) =
    FixProposal(id, null, "t$id", "e", path, listOf(edit), FixOrigin.RULE, Confidence.HIGH, TextEdits.hash(text))

class FixManagerTest {
    @Test fun applyWritesAndUndoRestores() = runBlocking {
        val f = FakeFiles(mapOf("A.kt" to SRC)); val m = FixManager(f)
        m.offer(listOf(proposal("1")))
        val pv = m.preview("1")!!
        assertEquals(1, pv.addedLines); assertEquals(1, pv.removedLines); assertEquals(2, pv.firstChangedLine)
        assertEquals(SRC, f.data["A.kt"]) // preview does not write
        assertTrue(m.apply("1") is FixResult.Applied)
        assertEquals("line1\nLINE2\nline3\nline4\n", f.data["A.kt"])
        assertEquals(1, m.undoDepth.value)
        assertTrue(m.apply("1") is FixResult.Invalid)
        assertTrue(m.undo() is UndoResult.Undone)
        assertEquals(SRC, f.data["A.kt"])
        assertEquals(FixStatus.PENDING, m.entry("1")!!.status)
        assertEquals(UndoResult.NothingToUndo, m.undo())
    }

    @Test fun staleFileIsNotTouched() = runBlocking {
        val f = FakeFiles(mapOf("A.kt" to SRC)); val m = FixManager(f)
        m.offer(listOf(proposal("1")))
        f.data["A.kt"] = SRC + "more\n"
        assertTrue(m.apply("1") is FixResult.Stale)
        assertNull(m.preview("1"))
        assertEquals(SRC + "more\n", f.data["A.kt"])
    }

    @Test fun undoRefusesToOverwriteNewerEdits() = runBlocking {
        val f = FakeFiles(mapOf("A.kt" to SRC)); val m = FixManager(f)
        m.offer(listOf(proposal("1"))); m.apply("1")
        f.data["A.kt"] = f.data["A.kt"] + "user edit\n"
        assertTrue(m.undo() is UndoResult.Conflict)
        assertTrue(f.data["A.kt"]!!.endsWith("user edit\n"))
        assertEquals(1, m.undoDepth.value)
    }

    @Test fun rejectRestoreAndReoffer() = runBlocking {
        val f = FakeFiles(mapOf("A.kt" to SRC)); val m = FixManager(f)
        m.offer(listOf(proposal("1")))
        assertTrue(m.reject("1")); assertEquals(FixStatus.REJECTED, m.entry("1")!!.status)
        m.offer(listOf(proposal("1"), proposal("2", edit = CodeEdit.delete(1))))
        assertEquals(FixStatus.REJECTED, m.entry("1")!!.status) // stays rejected
        assertEquals(FixStatus.PENDING, m.entry("2")!!.status)
        assertTrue(m.restore("1")); assertEquals(FixStatus.PENDING, m.entry("1")!!.status)
        assertFalse(m.reject("nope")); assertEquals(FixResult.NotFound, m.apply("nope"))
    }

    @Test fun writeFailureLeavesNoHistory() = runBlocking {
        val f = FakeFiles(mapOf("A.kt" to SRC)); val m = FixManager(f)
        m.offer(listOf(proposal("1"))); f.failWrites = true
        assertTrue(m.apply("1") is FixResult.Failed)
        assertEquals(0, m.undoDepth.value); assertEquals(FixStatus.PENDING, m.entry("1")!!.status)
    }

    @Test fun twoFixesUndoInReverseOrder() = runBlocking {
        val f = FakeFiles(mapOf("A.kt" to SRC)); val m = FixManager(f)
        val p1 = proposal("1")
        val after1 = "line1\nLINE2\nline3\nline4\n"
        val p2 = proposal("2", text = after1, edit = CodeEdit.replace(3, "LINE3"))
        m.offer(listOf(p1, p2))
        assertTrue(m.apply("1") is FixResult.Applied); assertTrue(m.apply("2") is FixResult.Applied)
        assertEquals("2", m.nextUndo()!!.id)
        m.undo(); assertEquals(after1, f.data["A.kt"])
        m.undo(); assertEquals(SRC, f.data["A.kt"])
    }

    @Test fun crlfFilesKeepLineEndings() {
        val r = TextEdits.apply("a\r\nb\r\nc", listOf(CodeEdit.replace(2, "B"))) as TextEdits.Result.Ok
        assertEquals("a\r\nB\r\nc", r.text)
    }

    @Test fun overlapAndOutOfRangeAreInvalid() {
        assertTrue(TextEdits.apply(SRC, listOf(CodeEdit(1, 3, listOf("x")), CodeEdit(2, 4, listOf("y")))) is TextEdits.Result.Invalid)
        assertTrue(TextEdits.apply(SRC, listOf(CodeEdit.replace(99, "x"))) is TextEdits.Result.Invalid)
    }
}

class AiLayerTest {
    private val files = FakeFiles(mapOf("src/Main.kt" to "fun main() {\n    val x = 1\n    println(y)\n}\n"))

    @Test fun noAiProviderIsHonest() = runBlocking {
        assertTrue(NoAiProvider.availability() is AiAvailability.NotReady)
        val r = NoAiProvider.analyze(AiDebugRequest(AiTask.EXPLAIN, null, null, true, emptyList(), "", emptyList()))
        assertTrue(r is AiResult.Unavailable)
    }

    @Test fun requestIsBuiltRedactedAndLimited() = runBlocking {
        val log = "e: file:///work/src/Main.kt:3:13 Unresolved reference: y\n"
        val analysis = BuildErrorAnalyzer(ProjectPathResolver(listOf("src/Main.kt"))).analyze(log)
        val entries = LogParser.parse(log)
        val req = AiRequestBuilder.build(AiTask.SUGGEST_FIXES, "Demo", "b1", analysis, analysis.issues, entries, files)
        assertTrue(req.issues.isNotEmpty())
        assertTrue(req.containsSourceCode)
        assertEquals("src/Main.kt", req.files.first().path)
        val prompt = AiPromptRenderer.render(req)
        assertTrue(prompt.contains("Unresolved reference"))
        assertTrue(prompt.contains("3:     println(y)"))
        val noFiles = AiRequestBuilder.build(AiTask.EXPLAIN, null, null, analysis, analysis.issues, entries, null)
        assertFalse(noFiles.containsSourceCode)
    }

    @Test fun secretFilesAreNeverIncluded() = runBlocking {
        val f = FakeFiles(mapOf("local.properties" to "sdk.dir=/x\nkey=abc\n"))
        val issue = BuildIssue("1", "r", ErrorCategory.GRADLE, Severity.ERROR, "bad", "local.properties", "local.properties", 1, null, null, IssueSource.BUILD_LOG, logIndex = 0)
        val req = AiRequestBuilder.build(AiTask.EXPLAIN, null, null, AnalysisResult(listOf(issue), true), listOf(issue), emptyList(), f)
        assertTrue(req.files.isEmpty())
    }

    @Test fun parserAcceptsFencedAndPlainJsonAndRejectsGarbage() {
        val json = """{"summary":"s","explanation":"e","patches":[{"path":"src/Main.kt","edits":[{"find":"println(y)","replace":"println(x)"}]},{"nopath":1}]}"""
        val a = AiResponseParser.parse("Here:\n```json\n$json\n```") as AiResponseParser.Parsed.Ok
        assertEquals(1, a.response.patches.size)
        assertTrue(AiResponseParser.parse(json) is AiResponseParser.Parsed.Ok)
        assertTrue(AiResponseParser.parse("no json here") is AiResponseParser.Parsed.Bad)
        assertTrue(AiResponseParser.parse("{broken") is AiResponseParser.Parsed.Bad)
        assertTrue(AiResponseParser.parse("{}") is AiResponseParser.Parsed.Bad)
    }

    private fun resp(vararg p: AiPatch) = AiDebugResponse("s", null, "e", p.toList())

    @Test fun searchReplacePatchBecomesApplicableFix() = runBlocking {
        val r = AiPatchConverter.convert(resp(AiPatch("src/Main.kt", "Use x", "why", listOf(AiPatchEdit("println(y)", "println(x)")))), files)
        val ok = r.single() as PatchConversion.Ok
        assertEquals(FixOrigin.AI, ok.proposal.origin)
        val m = FixManager(files); m.offer(listOf(ok.proposal))
        assertTrue(m.apply(ok.proposal.id) is FixResult.Applied)
        assertEquals("fun main() {\n    val x = 1\n    println(x)\n}\n", files.data["src/Main.kt"])
        m.undo()
        assertTrue(files.data["src/Main.kt"]!!.contains("println(y)"))
    }

    @Test fun badPatchesAreRejectedNotGuessed() = runBlocking {
        suspend fun rej(p: AiPatch) = AiPatchConverter.convert(resp(p), files).single() is PatchConversion.Rejected
        assertTrue(rej(AiPatch("src/Main.kt", "t", "e", listOf(AiPatchEdit("does not exist", "x")))))
        assertTrue(rej(AiPatch("src/Main.kt", "t", "e", listOf(AiPatchEdit("    ", "x")))))   // occurs several times
        assertTrue(rej(AiPatch("../etc/passwd", "t", "e", listOf(AiPatchEdit("a", "b")))))
        assertTrue(rej(AiPatch("/abs.kt", "t", "e", listOf(AiPatchEdit("a", "b")))))
        assertTrue(rej(AiPatch("missing.kt", "t", "e", listOf(AiPatchEdit("a", "b")))))
        assertTrue(rej(AiPatch("src/Main.kt", "t", "e", listOf(AiPatchEdit("val x = 1", "val x = 1")))))
    }

    @Test fun unifiedDiffPatchWorks() = runBlocking {
        val diff = "--- a/src/Main.kt\n+++ b/src/Main.kt\n@@ -2,3 +2,3 @@\n     val x = 1\n-    println(y)\n+    println(x)\n }\n"
        val ok = AiPatchConverter.convert(resp(AiPatch("src/Main.kt", "d", "e", unifiedDiff = diff)), files).single() as PatchConversion.Ok
        val m = FixManager(files); m.offer(listOf(ok.proposal)); m.apply(ok.proposal.id)
        assertTrue(files.data["src/Main.kt"]!!.contains("println(x)"))
        val wrong = diff.replace("println(y)", "println(zzz)")
        assertTrue(AiPatchConverter.convert(resp(AiPatch("src/Main.kt", "d", "e", unifiedDiff = wrong)), files).single() is PatchConversion.Rejected)
    }
}
