package com.almus.appcreatorpro.debug

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the AI debugger crash.
 *
 * Root causes (both reproduced on the JVM before the fix):
 *  1. `LogRedactor` / `LogCleaner` used patterns whose cost grows with the SQUARE of a line's length (a 20,000 character
 *     line took ~20 s, 40,000 took ~80 s). They ran on the main thread when the request was prepared, so the app froze
 *     and Android killed it.
 *  2. Only `AiException` was caught below the screen. Any other exception escaped `askAi` into `viewModelScope.launch`,
 *     which has no handler, and an uncaught exception there ends the process.
 */
class AiDebuggerCrashFixTest {
    private val path = "app/src/main/java/com/x/Calc.kt"
    private val source = "package com.x\n\nclass Calc {\n    fun f(value: Int) = labl + value\n}\n"
    private val log = "e: file:///home/runner/work/p/p/$path:4:24 Unresolved reference: labl\n\nFAILURE: Build failed with an exception.\n* What went wrong:\nExecution failed for task ':app:compileDebugKotlin'.\n> Compilation error. See log for more details\n\nBUILD FAILED in 3s\n"

    private val scopes = ArrayList<CoroutineScope>()
    @After fun tearDown() { scopes.forEach { it.cancel() } }
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    /** A provider whose behaviour each test scripts. */
    private class Scripted(var behaviour: suspend (AiDebugRequest) -> AiResult) : AiDebugProvider {
        val calls = AtomicInteger()
        val requests = java.util.Collections.synchronizedList(ArrayList<AiDebugRequest>())
        override val id = "scripted"
        override val displayName = "Scripted"
        override suspend fun availability(): AiAvailability = AiAvailability.Ready
        override suspend fun analyze(request: AiDebugRequest): AiResult { calls.incrementAndGet(); requests += request; return behaviour(request) }
    }

    private class Files(val data: MutableMap<String, String>) : ProjectFileAccess {
        @Volatile var readFailure: Throwable? = null
        override suspend fun read(path: String): String { readFailure?.let { throw it }; return data[path] ?: throw java.io.FileNotFoundException(path) }
        override suspend fun write(path: String, content: String) { data[path] = content }
    }

    private fun answer(vararg patches: AiPatch) = AiResult.Success(AiDebugResponse("summary", "cause", "explanation", patches.toList()))
    private val goodPatch get() = AiPatch(path, "Fix name", "typo", listOf(AiPatchEdit("labl + value", "value + value")))

    private fun session(provider: AiDebugProvider, files: Files = Files(mutableMapOf(path to source)), sink: (String, Throwable) -> Unit = { _, _ -> }, logText: String = log): Pair<DebugSession, Files> {
        val s = DebugSession("Sample", "b1", files, { files.data.keys.sorted() }, { RawBuildLog(logText, null) }, provider, errorSink = sink)
        return s to files
    }

    private suspend fun loaded(provider: AiDebugProvider, files: Files = Files(mutableMapOf(path to source)), sink: (String, Throwable) -> Unit = { _, _ -> }): Pair<DebugSession, Files> =
        session(provider, files, sink).also { it.first.loadBuildLog() }

    private suspend fun AiDebugController.awaitState(predicate: (AiUi) -> Boolean): AiUi = withTimeout(10_000) { state.first(predicate) }
    private suspend fun waitUntil(what: String, cond: () -> Boolean) { withTimeout(10_000) { while (!cond()) delay(5) }; assertTrue(what, cond()) }

    // ---------------------------------------------------------------------------------------- root cause 1: slow redaction

    @Test fun redactionOfAVeryLongLineIsFastAndStillRemovesSecrets() {
        val long = "a.".repeat(100_000)                                // 200,000 characters: ~20 minutes with the old pattern
        val started = System.nanoTime()
        LogCleaner.redact(long)
        LogCleaner.redact(long + " GITHUB_TOKEN=abc123")
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("redaction took $millis ms", millis < 3_000)
        assertEquals("GITHUB_TOKEN=***", LogCleaner.redact("GITHUB_TOKEN=abc123"))
        assertEquals("db.password: ***", LogCleaner.redact("db.password: hunter2"))
        assertEquals("key-store_password = ***", LogCleaner.redact("key-store_password = s3cret"))
        assertTrue(LogCleaner.redact("see Foo.kt:12:5 and my_token=zzz").let { it.contains("Foo.kt:12:5") && it.endsWith("my_token=***") })
    }

    @Test fun logRedactorHandlesLongDashedAndUnderscoredWordsInLinearTime() {
        for (unit in listOf("a-", "a_", "tok.", "x/")) {
            val started = System.nanoTime()
            com.almus.appcreatorpro.domain.github.LogRedactor.redact(unit.repeat(100_000))
            assertTrue("'$unit' took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 3_000)
        }
    }

    @Test fun aHugeIssueMessageAndLogLineAreCutBeforeTheyLeaveTheDevice() = runBlocking {
        val hugeLog = "e: file:///home/runner/work/p/p/$path:4:24 Unresolved reference: " + "a.".repeat(150_000) + "\n" + log
        val (s, _) = session(Scripted { answer() }, logText = hugeLog)
        s.loadBuildLog()
        assertTrue("log analysed", s.state.value.buildIssues.isNotEmpty())
        val started = System.nanoTime()
        val req = s.buildAiRequest(AiTask.SUGGEST_FIXES, includeCode = true)
        assertTrue("building the request took too long", (System.nanoTime() - started) / 1_000_000 < 3_000)
        assertTrue(req.issues.all { it.message.length <= AiRequestBuilder.MAX_MESSAGE_CHARS + 40 })
        assertTrue(req.logExcerpt.length <= AiRequestBuilder.MAX_LOG_CHARS)
        assertTrue(req.files.all { f -> f.snippet.lines().all { it.length <= AiRequestBuilder.MAX_LINE_CHARS + 40 } })
        assertTrue("prompt is bounded", AiPromptRenderer.render(req).length < 70_000)
    }

    @Test fun aMinifiedProjectFileLineIsCutInTheSnippet() = runBlocking {
        val minified = "package com.x\n\nclass Calc {\n    fun f(value: Int) = labl + value // " + "z".repeat(500_000) + "\n}\n"
        val (s, _) = loaded(Scripted { answer() }, Files(mutableMapOf(path to minified)))
        val req = s.buildAiRequest(AiTask.SUGGEST_FIXES, includeCode = true)
        assertTrue(req.files.isNotEmpty())
        assertTrue(req.files.single().snippet.length < 5_000)
    }

    @Test fun veryLargeLogsKeepTheTailAndVeryLongLinesAreShortened() {
        val filler = "Task :app:compile some ordinary build output line\n"
        val big = filler.repeat((LogLimits.MAX_LOG_CHARS / filler.length) + 5_000) + "e: Foo.kt:1:1 THE-REAL-ERROR\n"
        val b = LogLimits.bound(big)
        assertTrue(b.text.length <= LogLimits.MAX_LOG_CHARS)
        assertTrue(b.text.endsWith("THE-REAL-ERROR\n"))
        assertTrue(b.text.startsWith("Task :app")) // started on a whole line, not in the middle of one
        assertNotNull(b.note)

        val longLine = LogLimits.bound("ok\n" + "x".repeat(LogLimits.MAX_LINE_CHARS * 3) + "\nend\n")
        assertTrue(longLine.text.lines().all { it.length <= LogLimits.MAX_LINE_CHARS + 20 })
        assertTrue(longLine.text.endsWith("end\n"))
        assertNotNull(longLine.note)

        val small = LogLimits.bound("a\nb\n")
        assertEquals("a\nb\n", small.text); assertNull(small.note)
    }

    @Test fun loadingAHugeLogDoesNotFailAndTheNoteExplainsTheCut() = runBlocking {
        val (s, _) = session(Scripted { answer() }, logText = log + "y".repeat(100_000) + "\n")
        s.loadBuildLog()
        val st = s.state.value
        assertTrue(st.logLoaded); assertFalse(st.loadingLog)
        assertTrue(st.logNote.orEmpty().contains("shortened"))
        assertTrue(st.buildIssues.isNotEmpty())
    }

    @Test fun cancellingTheLogLoadNeverLeavesTheSpinnerOn() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val files = Files(mutableMapOf(path to source))
        val s = DebugSession("p", "b1", files, { listOf(path) }, { gate.await(); RawBuildLog(log, null) })
        val job = async(Dispatchers.Default) { s.loadBuildLog() }
        waitUntil("loading started") { s.state.value.loadingLog }
        job.cancel()
        job.join()
        assertFalse(s.state.value.loadingLog)
    }

    @Test fun aLogThatCannotBeLoadedBecomesANoteAndIsReported() = runBlocking {
        val seen = ArrayList<Throwable>()
        val s = DebugSession("p", "b1", Files(mutableMapOf()), { emptyList() }, { throw IllegalStateException("no network") }, errorSink = { _, t -> seen += t })
        s.loadBuildLog()
        assertTrue(s.state.value.logNote.orEmpty().contains("could not be loaded"))
        assertFalse(s.state.value.loadingLog)
        assertEquals(1, seen.size)
    }

    // ---------------------------------------------------------------------------------------- root cause 2: exceptions escaped

    @Test fun anUnexpectedProviderExceptionBecomesAFailedOutcomeInsteadOfCrashing() = runBlocking {
        for (boom in listOf<Throwable>(IllegalStateException("boom"), java.security.GeneralSecurityException("keystore"), java.io.IOException("reset"), NullPointerException(), ClassCastException(), IndexOutOfBoundsException())) {
            val (s, _) = loaded(Scripted { throw boom })
            val out = s.askAi(s.buildAiRequest(AiTask.EXPLAIN, includeCode = false))
            out as? AiOutcome.Failed ?: fail("expected Failed for $boom but got $out")
            assertSame("the exception is kept for logging", boom, (out as AiOutcome.Failed).cause)
            assertTrue(out.message.isNotBlank())
        }
    }

    @Test fun outOfMemoryAndStackOverflowAreMessagesButOtherErrorsAreNotHidden() = runBlocking {
        val (s1, _) = loaded(Scripted { throw OutOfMemoryError("heap") })
        assertTrue((s1.askAi(s1.buildAiRequest(AiTask.EXPLAIN, false)) as AiOutcome.Failed).message.contains("memory"))
        val (s2, _) = loaded(Scripted { throw StackOverflowError() })
        assertTrue(s2.askAi(s2.buildAiRequest(AiTask.EXPLAIN, false)) is AiOutcome.Failed)
        val (s3, _) = loaded(Scripted { throw NoClassDefFoundError("Broken") })
        try { s3.askAi(s3.buildAiRequest(AiTask.EXPLAIN, false)); fail("a broken app must not be reported as a friendly message") } catch (e: NoClassDefFoundError) { /* expected */ }
    }

    @Test fun cancellationIsRethrownNotTurnedIntoAFailure() = runBlocking {
        val (s, _) = loaded(Scripted { throw CancellationException("stop") })
        try { s.askAi(s.buildAiRequest(AiTask.EXPLAIN, false)); fail("cancellation must propagate") } catch (e: CancellationException) { /* expected */ }
    }

    @Test fun failedAndUnavailableResultsAreForwardedAsTheyAre() = runBlocking {
        val cause = RuntimeException("x")
        val (s, _) = loaded(Scripted { AiResult.Failed("nope", true, cause) })
        val f = s.askAi(s.buildAiRequest(AiTask.EXPLAIN, false)) as AiOutcome.Failed
        assertEquals("nope", f.message); assertTrue(f.retryable); assertSame(cause, f.cause)
        val (s2, _) = loaded(Scripted { AiResult.Unavailable("not set up") })
        assertEquals(AiOutcome.Unavailable("not set up"), s2.askAi(s2.buildAiRequest(AiTask.EXPLAIN, false)))
    }

    @Test fun aFailureWhileCheckingTheAnswersPatchesIsAFailedOutcomeNotACrash() = runBlocking {
        val files = Files(mutableMapOf(path to source))
        val (s, _) = loaded(Scripted { files.readFailure = OutOfMemoryError("heap"); answer(goodPatch) }, files)
        val out = s.askAi(s.buildAiRequest(AiTask.GENERATE_PATCH, includeCode = true))
        assertTrue("got $out", out is AiOutcome.Failed)
        assertTrue(s.fixes.entries.value.isEmpty())
    }

    @Test fun oneBadPatchDoesNotLoseTheOthers() = runBlocking {
        val (s, _) = loaded(Scripted { answer(AiPatch("../../etc/passwd", "bad", "bad", listOf(AiPatchEdit("a", "b"))), goodPatch) })
        val out = s.askAi(s.buildAiRequest(AiTask.GENERATE_PATCH, includeCode = true)) as AiOutcome.Answer
        assertEquals(1, out.rejectedPatches.size)
        assertEquals(1, s.fixes.entries.value.size)
    }

    // ---------------------------------------------------------------------------------------- approval workflow preserved

    @Test fun anAnswerNeverChangesFilesAndFixesStayPendingUntilTheUserApplies() = runBlocking {
        val scope = newScope()
        val (s, files) = loaded(Scripted { answer(goodPatch) })
        val c = AiDebugController(scope, { s })
        c.send(s.buildAiRequest(AiTask.GENERATE_PATCH, includeCode = true))
        val done = c.awaitState { it is AiUi.Done } as AiUi.Done
        assertTrue(done.outcome is AiOutcome.Answer)
        assertEquals(source, files.data[path])                                 // nothing was written
        val fix = s.fixes.entries.value.single()
        assertEquals(FixStatus.PENDING, fix.status); assertEquals(FixOrigin.AI, fix.proposal.origin)
        assertTrue(s.applyFix(fix.proposal.id) is FixResult.Applied)           // only the explicit approval writes
        assertTrue(files.data[path]!!.contains("value + value"))
    }

    // ---------------------------------------------------------------------------------------- controller: single flight, cancel, timeout, retry

    @Test fun duplicateRequestsAreIgnoredWhileOneIsRunning() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val provider = Scripted { gate.await(); answer() }
        val (s, _) = loaded(provider)
        val c = AiDebugController(newScope(), { s })
        val req = s.buildAiRequest(AiTask.EXPLAIN, false)

        // 20 simultaneous taps on "Send": exactly one is accepted.
        val accepted = List(20) { async(Dispatchers.Default) { c.send(req) } }.awaitAll().count { it }
        assertEquals(1, accepted)
        assertTrue(c.state.value is AiUi.Working)
        assertFalse("a second kind of request is ignored too", c.prepare(AiTask.SUGGEST_FIXES, true))
        assertFalse(c.retry())
        gate.complete(Unit)
        c.awaitState { it is AiUi.Done }
        assertEquals(1, provider.calls.get())
    }

    @Test fun cancelStopsTheRequestAndTheNetworkWorkAndReturnsToIdle() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val provider = Scripted { try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        val (s, _) = loaded(provider)
        val c = AiDebugController(newScope(), { s })
        c.send(s.buildAiRequest(AiTask.EXPLAIN, false))
        waitUntil("request started") { provider.calls.get() == 1 }
        c.cancel()
        assertEquals(AiUi.Idle, c.state.value)
        withTimeout(10_000) { cancelled.await() }                              // the provider (the HTTP call) really was cancelled
        delay(50)
        assertEquals("the cancelled request must not overwrite the state later", AiUi.Idle, c.state.value)

        provider.behaviour = { answer() }                                      // and a new request works afterwards
        assertTrue(c.send(s.buildAiRequest(AiTask.EXPLAIN, false)))
        assertTrue((c.awaitState { it is AiUi.Done } as AiUi.Done).outcome is AiOutcome.Answer)
    }

    @Test fun aRequestThatNeverAnswersTimesOutWithARetryableMessage() = runBlocking {
        val seen = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val provider = Scripted { awaitCancellation() }
        val (s, _) = loaded(provider)
        val c = AiDebugController(newScope(), { s }, timeoutMillis = 200, errorSink = { _, t -> seen += t })
        val req = s.buildAiRequest(AiTask.EXPLAIN, false)
        c.send(req)
        val done = c.awaitState { it is AiUi.Done } as AiUi.Done
        val failed = done.outcome as AiOutcome.Failed
        assertTrue(failed.message, failed.message.contains("did not answer"))
        assertTrue(failed.retryable)
        assertSame(req, done.request)
        assertTrue(seen.single() is TimeoutCancellationException)              // reported, not swallowed
        assertFalse(c.isBusy)                                                  // loading state recovered
    }

    @Test fun aFailureIsShownLoggedAndCanBeRetriedWithTheSameRequest() = runBlocking {
        val seen = java.util.Collections.synchronizedList(ArrayList<Pair<String, Throwable>>())
        var first = true
        val provider = Scripted { if (first) { first = false; throw IllegalStateException("boom") } else answer() }
        val (s, _) = loaded(provider)
        val c = AiDebugController(newScope(), { s }, errorSink = { m, t -> seen += m to t })
        val req = s.buildAiRequest(AiTask.EXPLAIN, false)
        c.send(req)
        val failed = (c.awaitState { it is AiUi.Done } as AiUi.Done).outcome as AiOutcome.Failed
        assertTrue(failed.message.contains("IllegalStateException"))
        assertEquals("boom", seen.single().second.message)

        assertTrue(c.retry())
        val done = c.awaitState { it is AiUi.Done && it.outcome is AiOutcome.Answer } as AiUi.Done
        assertEquals(2, provider.calls.get())
        assertSame("Retry sends exactly the request the user confirmed", req, provider.requests[1])
        assertSame(req, done.request)
    }

    @Test fun theStateIsRecoveredWhenTheOwningScopeIsCancelled() = runBlocking {
        val scope = newScope()
        val provider = Scripted { awaitCancellation() }
        val (s, _) = loaded(provider)
        val c = AiDebugController(scope, { s })
        c.send(s.buildAiRequest(AiTask.EXPLAIN, false))
        waitUntil("request started") { provider.calls.get() == 1 }
        scope.cancel()                                                         // e.g. the screen's ViewModel was cleared
        assertEquals(AiUi.Idle, c.awaitState { it == AiUi.Idle })
    }

    @Test fun preparingFailureIsAMessageAndRetryBuildsTheRequestAgain() = runBlocking {
        val files = Files(mutableMapOf(path to source))
        val (s, _) = loaded(Scripted { answer() }, files)
        val seen = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val c = AiDebugController(newScope(), { s }, errorSink = { _, t -> seen += t })
        files.readFailure = OutOfMemoryError("heap")
        assertTrue(c.prepare(AiTask.SUGGEST_FIXES, includeCode = true))
        val done = c.awaitState { it is AiUi.Done } as AiUi.Done
        assertTrue((done.outcome as AiOutcome.Failed).message.contains("memory"))
        assertNull(done.request)
        assertEquals(1, seen.size)

        files.readFailure = null
        assertTrue(c.retry())
        val confirm = c.awaitState { it is AiUi.Confirm } as AiUi.Confirm
        assertTrue(confirm.request.containsSourceCode)
    }

    @Test fun withoutASessionTheRequestFailsWithAMessageAndNothingIsStarted() = runBlocking {
        val c = AiDebugController(newScope(), { null })
        assertFalse(c.send(AiDebugRequest(AiTask.EXPLAIN, null, null, true, emptyList(), "", emptyList())))
        val done = c.state.value as AiUi.Done
        assertTrue((done.outcome as AiOutcome.Failed).message.contains("loading"))
        assertFalse(c.isBusy)
    }

    @Test fun cancelAlsoClosesAConfirmationAndAFinishedAnswer() = runBlocking {
        val (s, _) = loaded(Scripted { answer() })
        val c = AiDebugController(newScope(), { s })
        c.prepare(AiTask.EXPLAIN, false)
        c.awaitState { it is AiUi.Confirm }
        c.cancel(); assertEquals(AiUi.Idle, c.state.value)
        c.send(s.buildAiRequest(AiTask.EXPLAIN, false))
        c.awaitState { it is AiUi.Done }
        c.cancel(); assertEquals(AiUi.Idle, c.state.value)
    }

    // ---------------------------------------------------------------------------------------- answer parsing

    @Test fun malformedEmptyAndOversizedAnswersAreRejectedWithoutThrowing() {
        fun bad(text: String): String = (AiResponseParser.parse(text) as AiResponseParser.Parsed.Bad).reason
        assertTrue(bad("").contains("empty"))
        assertTrue(bad("   \n\t ").contains("empty"))
        assertTrue(bad("I cannot help with that.").contains("JSON"))
        assertTrue(bad("{\"summary\": \"cut off").contains("JSON") || bad("{\"summary\": \"cut off").contains("valid"))
        assertTrue(bad("{ not json }").isNotBlank())
        assertTrue(bad("[1,2,3]").isNotBlank())
        assertTrue(bad("{\"summary\": 5, \"explanation\": null, \"patches\": \"x\"}").contains("neither"))
        assertTrue(bad("x".repeat(AiResponseParser.MAX_ANSWER_CHARS + 1)).contains("too long"))
        assertTrue(bad("{".repeat(10_000)).isNotBlank())                       // deep nesting: no StackOverflowError
    }

    @Test fun wellFormedAnswersStillParseAndPatchesAreCapped() {
        val fenced = "Here you go:\n```json\n{\"summary\":\"s\",\"rootCause\":null,\"explanation\":\"e\",\"patches\":[]}\n```"
        assertEquals("s", (AiResponseParser.parse(fenced) as AiResponseParser.Parsed.Ok).response.summary)
        val many = (1..50).joinToString(",") { """{"path":"a$it.kt","title":"t","explanation":"x","edits":[{"find":"a","replace":"b"}]}""" }
        val ok = AiResponseParser.parse("""{"summary":"s","explanation":"e","patches":[$many]}""") as AiResponseParser.Parsed.Ok
        assertEquals(AiResponseParser.MAX_PATCHES, ok.response.patches.size)
        val odd = AiResponseParser.parse("""{"summary":"s","patches":[1,"x",{"path":5},{"path":"a.kt"},{"path":"b.kt","edits":[{"find":1}]}]}""") as AiResponseParser.Parsed.Ok
        assertTrue(odd.response.patches.isEmpty())
    }

    // ---------------------------------------------------------------------------------------- failure messages

    @Test fun failureMessagesAreFriendlyAndNeverExposeStackTraces() {
        fun msg(t: Throwable) = AiFailures.describe(t).first
        assertTrue(msg(java.net.SocketTimeoutException()).contains("in time"))
        assertTrue(msg(java.net.UnknownHostException("x")).contains("internet"))
        assertTrue(msg(javax.net.ssl.SSLHandshakeException("x")).contains("secure"))
        assertTrue(msg(java.io.IOException("x")).contains("interrupted"))
        assertTrue(msg(java.security.GeneralSecurityException("x")).contains("access code"))
        assertTrue(msg(OutOfMemoryError()).contains("memory"))
        assertTrue(msg(IllegalStateException("internal detail")).let { it.contains("IllegalStateException") && !it.contains("internal detail") })
        assertFalse(AiFailures.isRecoverable(NoClassDefFoundError()))
        assertTrue(AiFailures.isRecoverable(OutOfMemoryError()))
    }
}
