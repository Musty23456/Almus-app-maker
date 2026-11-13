package com.almus.appcreatorpro.debug

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Phase 22, AI layer: the 8-section answer, confidence calibration, the report, and follow-up questions. */
class AiDiagnosisTest {
    private val calc = "app/src/main/java/com/example/broken/Calculator.kt"

    private fun conf(level: AnswerConfidence) = AiConfidence(level, "because")
    private fun local(level: ConfidenceLevelForTest, sufficient: Boolean = true, score: Int = 70) =
        DiagnosticConfidence(level.l, score, listOf("local reason"), sufficient)

    private enum class ConfidenceLevelForTest(val l: Confidence) { HIGH(Confidence.HIGH), MEDIUM(Confidence.MEDIUM), LOW(Confidence.LOW) }

    // ---- parser ----------------------------------------------------------------------------------------------------

    @Test fun parserReadsTheEightSectionAnswer() {
        val json = """{"summary":"S","category":"Kotlin","rootCause":"RC","explanation":"E","recommendedSolution":"Do X",
            "affectedFiles":[{"path":"a/B.kt","line":4,"note":"n"}],
            "codeSuggestions":[{"path":"a/B.kt","description":"d","code":"val x = 1"}],
            "confidence":{"level":"medium","reason":"r"},"missingInformation":["the full log"],"patches":[]}"""
        val r = (AiResponseParser.parse(json) as AiResponseParser.Parsed.Ok).response
        assertEquals("Kotlin", r.category); assertEquals("RC", r.rootCause); assertEquals("Do X", r.recommendedSolution)
        assertEquals(AiAffectedFile("a/B.kt", 4, "n"), r.affectedFiles.single())
        assertEquals("val x = 1", r.codeSuggestions.single().code)
        assertEquals(AnswerConfidence.MEDIUM, r.confidence?.level)
        assertEquals(listOf("the full log"), r.missingInformation)
    }

    @Test fun olderShapedAnswerStillParsesWithoutInventingAnything() {
        val r = (AiResponseParser.parse("""{"summary":"S","rootCause":"","explanation":"E","patches":[]}""") as AiResponseParser.Parsed.Ok).response
        assertNull(r.rootCause); assertNull(r.confidence)
        assertTrue(r.affectedFiles.isEmpty()); assertTrue(r.codeSuggestions.isEmpty()); assertTrue(r.missingInformation.isEmpty())
    }

    @Test fun garbageIsRejectedNotThrown() {
        assertTrue(AiResponseParser.parse("") is AiResponseParser.Parsed.Bad)
        assertTrue(AiResponseParser.parse("no json here") is AiResponseParser.Parsed.Bad)
        assertTrue(AiResponseParser.parse("""{"summary":"S","confidence":{"level":"banana"},"explanation":"E"}""").let { it is AiResponseParser.Parsed.Ok && it.response.confidence == null || it is AiResponseParser.Parsed.Bad })
    }

    // ---- calibration -----------------------------------------------------------------------------------------------

    @Test fun aiMayBeOneStepAboveLocalNeverTwo() {
        val low = local(ConfidenceLevelForTest.LOW, score = 45)
        assertEquals(AnswerConfidence.MEDIUM, ConfidenceCalibrator.calibrate(conf(AnswerConfidence.HIGH), low).level)
        assertEquals(AnswerConfidence.LOW, ConfidenceCalibrator.calibrate(conf(AnswerConfidence.LOW), low).level)
        assertNotNull(ConfidenceCalibrator.calibrate(conf(AnswerConfidence.HIGH), low).note)
    }

    @Test fun insufficientFromTheAiIsAlwaysHonored() {
        val high = local(ConfidenceLevelForTest.HIGH, score = 95)
        assertEquals(AnswerConfidence.INSUFFICIENT, ConfidenceCalibrator.calibrate(conf(AnswerConfidence.INSUFFICIENT), high).level)
    }

    @Test fun noStatedConfidenceShowsTheLocalLevelAndSaysSo() {
        val c = ConfidenceCalibrator.calibrate(null, local(ConfidenceLevelForTest.MEDIUM))
        assertEquals(AnswerConfidence.MEDIUM, c.level)
        assertTrue(c.note!!.contains("did not state"))
    }

    @Test fun noNamedRootCauseCapsAtMedium() {
        val c = ConfidenceCalibrator.calibrate(conf(AnswerConfidence.HIGH), local(ConfidenceLevelForTest.HIGH), rootCauseNamed = false)
        assertTrue(c.level.rank <= AnswerConfidence.MEDIUM.rank)
    }

    // ---- session: report, AI merge, follow-up ----------------------------------------------------------------------

    private class Fake(
        val response: AiDebugResponse,
        val followUps: MutableList<AiFollowUpRequest> = ArrayList(),
        val followUpAnswer: FollowUpResult = FollowUpResult.Success(AiFollowUpAnswer("A", AiConfidence(AnswerConfidence.HIGH, null), emptyList(), emptyList())),
    ) : AiDebugProvider {
        override val id = "fake"; override val displayName = "Fake"
        override suspend fun availability() = AiAvailability.Ready
        override suspend fun analyze(request: AiDebugRequest): AiResult = AiResult.Success(response)
        override suspend fun followUp(request: AiFollowUpRequest): FollowUpResult { followUps += request; return followUpAnswer }
    }

    private fun session(ai: AiDebugProvider = NoAiProvider): DebugSession {
        val files = FakeFiles(Samples.project("kotlin-errors"))
        return DebugSession("Sample", "b1", files, { files.data.keys.sorted() }, { RawBuildLog(Samples.text("kotlin-errors/build.log"), null) }, ai)
    }

    @Test fun localReportHasAllEightSectionsAndNoAiClaim() = runBlocking {
        val s = session(); s.loadBuildLog()
        val r = s.diagnosisReport()!!
        assertFalse(r.aiAssisted)
        val md = r.toMarkdown()
        for (h in listOf("Error Summary", "Error Category", "Root Cause", "Affected Files", "Technical Explanation", "Recommended Solution", "Suggested Code Changes")) assertTrue("missing $h", md.contains("## $h"))
        assertTrue(md.contains("Confidence"))
    }

    @Test fun reportIsNullWhenThereIsNothingToDiagnose() = runBlocking {
        val files = FakeFiles(emptyMap())
        val s = DebugSession("x", "b1", files, { emptyList() }, { RawBuildLog("BUILD SUCCESSFUL in 3s\n", null) })
        s.loadBuildLog()
        assertNull(s.diagnosisReport())
        assertTrue(s.askFollowUp("why?", null, emptyList()) is FollowUpOutcome.Unavailable)
    }

    @Test fun aiFilesThatDoNotExistAreFlaggedUnverified() = runBlocking {
        val resp = AiDebugResponse(
            "S", "RC", "E", emptyList(),
            category = "Kotlin", recommendedSolution = "Fix it",
            affectedFiles = listOf(AiAffectedFile(calc, 3, null), AiAffectedFile("does/not/Exist.kt", 1, null)),
            codeSuggestions = listOf(AiCodeSuggestion(calc, "rename", "label + value")),
            confidence = conf(AnswerConfidence.HIGH),
        )
        val s = session(Fake(resp)); s.loadBuildLog()
        s.askAi(s.buildAiRequest(AiTask.DIAGNOSE, includeCode = true))
        val r = s.diagnosisReport()!!
        assertTrue(r.aiAssisted)
        val ghost = r.affectedFiles.first { it.label.contains("Exist.kt") }
        assertFalse(ghost.verified); assertNull(ghost.path)
        assertTrue(r.codeChanges.any { it.source == ChangeSource.AI_SUGGESTION })
    }

    @Test fun followUpSendsOnlyConfirmedExcerptAndNeverNewCode() = runBlocking {
        val fake = Fake(AiDebugResponse("S", "RC", "E", emptyList()))
        val s = session(fake); s.loadBuildLog()
        // No earlier confirmed request with code: no source code may go out.
        val out = s.askFollowUp("Why did this happen?", null, emptyList())
        assertTrue(out is FollowUpOutcome.Answer)
        assertTrue(fake.followUps.single().files.isEmpty())
        assertFalse(s.followUpIncludesCode())
        // After a diagnosis request without code, still none.
        s.askAi(s.buildAiRequest(AiTask.DIAGNOSE, includeCode = false))
        s.askFollowUp("And now?", null, emptyList())
        assertTrue(fake.followUps.last().files.isEmpty())
    }

    @Test fun followUpRedactsSecretsInTheQuestionAndBoundsIt() {
        val q = FollowUpPrompt.cleanQuestion("token ghp_abcdefghijklmnopqrstuvwxyz0123456789AB " + "x".repeat(5000))!!
        assertFalse(q.contains("ghp_abcdefghijklmnopqrstuvwxyz0123456789AB"))
        assertTrue(q.length <= FollowUpPrompt.MAX_QUESTION_CHARS + 50)
        assertNull(FollowUpPrompt.cleanQuestion("   \u0000  "))
        val h = FollowUpPrompt.boundHistory((1..20).map { FollowUpExchange("q$it", "a".repeat(5000)) })
        assertEquals(FollowUpPrompt.MAX_HISTORY, h.size)
        assertTrue(h.all { it.answer.length <= FollowUpPrompt.MAX_HISTORY_ANSWER_CHARS })
    }

    @Test fun followUpParserHandlesJsonPlainTextAndNothing() {
        val ok = FollowUpParser.parse("""{"answer":"Because X","confidence":{"level":"low","reason":"r"},"missingInformation":["m"],"codeSuggestions":[]}""") as FollowUpParser.Parsed.Ok
        assertEquals("Because X", ok.answer.answer); assertEquals(AnswerConfidence.LOW, ok.answer.confidence?.level)
        val plain = FollowUpParser.parse("Just prose, no JSON.") as FollowUpParser.Parsed.Ok
        assertNull(plain.answer.confidence) // none is invented
        assertTrue(FollowUpParser.parse("") is FollowUpParser.Parsed.Bad)
    }

    @Test fun followUpConfidenceIsCalibratedAgainstLocalEvidence() = runBlocking {
        val fake = Fake(AiDebugResponse("S", "RC", "E", emptyList()), followUpAnswer = FollowUpResult.Success(AiFollowUpAnswer("A", null, emptyList(), emptyList())))
        val s = session(fake); s.loadBuildLog()
        val a = s.askFollowUp("why", null, emptyList()) as FollowUpOutcome.Answer
        assertNotNull(a.confidence.note) // "the AI did not state how sure it is"
    }

    @Test fun noAiProviderGivesUnavailableNotAnInventedAnswer() = runBlocking {
        val s = session(); s.loadBuildLog()
        assertTrue(s.askFollowUp("why", null, emptyList()) is FollowUpOutcome.Unavailable)
    }

    // ---- controller ------------------------------------------------------------------------------------------------

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun FollowUpController.await(cond: (FollowUpState) -> Boolean) = withTimeout(5_000) { while (!cond(state.value)) delay(10) }

    @Test fun controllerAnswersOneQuestionAtATimeAndNeverStaysPending() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val provider = object : AiDebugProvider {
            override val id = "g"; override val displayName = "g"
            override suspend fun availability() = AiAvailability.Ready
            override suspend fun analyze(request: AiDebugRequest): AiResult = AiResult.Failed("n/a", false)
            override suspend fun followUp(request: AiFollowUpRequest): FollowUpResult {
                gate.await(); return FollowUpResult.Success(AiFollowUpAnswer("done", null, emptyList(), emptyList()))
            }
        }
        val s = session(provider); s.loadBuildLog()
        val c = FollowUpController(scope(), { s })
        assertTrue(c.ask("first"))
        assertFalse(c.ask("second")) // one at a time
        assertTrue(c.state.value.busy)
        gate.complete(Unit)
        c.await { !it.busy }
        assertEquals(1, c.state.value.turns.size)
        assertTrue(c.state.value.turns.single().outcome is FollowUpOutcome.Answer)
        assertFalse(c.ask("   ")) // empty question is ignored
    }

    @Test fun controllerCancelRemovesPendingTurnAndFailureCanBeRetried() = runBlocking {
        var fail = true
        val provider = object : AiDebugProvider {
            override val id = "f"; override val displayName = "f"
            override suspend fun availability() = AiAvailability.Ready
            override suspend fun analyze(request: AiDebugRequest): AiResult = AiResult.Failed("n/a", false)
            override suspend fun followUp(request: AiFollowUpRequest): FollowUpResult =
                if (fail) FollowUpResult.Failed("network down", true) else { delay(60_000); FollowUpResult.Unavailable("never") }
        }
        val s = session(provider); s.loadBuildLog()
        val c = FollowUpController(scope(), { s })
        c.ask("q1"); c.await { !it.busy && it.turns.isNotEmpty() }
        assertTrue(c.state.value.turns.single().outcome is FollowUpOutcome.Failed)
        fail = false
        assertTrue(c.retry()) // same question again
        assertTrue(c.state.value.busy)
        c.cancel()
        assertFalse(c.state.value.busy); assertTrue(c.state.value.turns.isEmpty())
        c.clear(); assertTrue(c.state.value.turns.isEmpty())
    }

    @Test fun controllerWithoutSessionReportsInsteadOfCrashing() {
        val c = FollowUpController(scope(), { null })
        assertFalse(c.ask("q"))
        assertFalse(c.state.value.busy)
        assertTrue(c.state.value.turns.single().outcome is FollowUpOutcome.Failed)
    }

    @Test fun controllerThrowingProviderBecomesAMessage() = runBlocking {
        val provider = object : AiDebugProvider {
            override val id = "t"; override val displayName = "t"
            override suspend fun availability() = AiAvailability.Ready
            override suspend fun analyze(request: AiDebugRequest): AiResult = AiResult.Failed("n/a", false)
            override suspend fun followUp(request: AiFollowUpRequest): FollowUpResult = throw java.io.IOException("socket closed")
        }
        val s = session(provider); s.loadBuildLog()
        val c = FollowUpController(scope(), { s }, errorSink = { _, _ -> })
        c.ask("q"); c.await { !it.busy && it.turns.isNotEmpty() }
        val o = c.state.value.turns.single().outcome as FollowUpOutcome.Failed
        assertTrue(o.message.isNotBlank())
    }
}
