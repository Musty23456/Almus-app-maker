package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.debug.AiDebugRequest
import com.almus.appcreatorpro.debug.AiResult
import com.almus.appcreatorpro.debug.AiTask
import com.almus.appcreatorpro.service.ai.GeminiBackendClient
import com.almus.appcreatorpro.service.github.InMemorySecretStore
import com.almus.appcreatorpro.testutil.FakeTransport
import com.almus.appcreatorpro.testutil.jsonResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** The Gemini debug provider must turn EVERY failure of the backend into an [AiResult], and only rethrow cancellation. */
class GeminiDebugProviderHardeningTest {
    private val request = AiDebugRequest(AiTask.EXPLAIN, "Notes", "b1", true, emptyList(), "e: Unresolved reference: Foo", emptyList())
    private val goodJson = """{"summary":"Missing import","rootCause":"c","explanation":"Add the import.","patches":[]}"""

    private fun provider(b: AiBackend) = GeminiDebugProvider(b)
    private fun failed(b: AiBackend): AiResult.Failed = runBlocking { provider(b).analyze(request) } as AiResult.Failed

    // ---- empty / malformed / cut-off answers
    @Test fun anEmptyAnswerIsReportedAsEmptyAndRetryable() {
        for (text in listOf("", "   ", "\n\n")) {
            val f = failed(FakeAiBackend().also { it.answerText(text) })
            assertTrue(f.message, f.message.contains("empty")); assertTrue(f.retryable)
        }
    }

    @Test fun anEmptyCutOffAnswerSaysItWasCutOff() {
        val f = failed(FakeAiBackend().also { it.answerText("", finish = "MAX_TOKENS") })
        assertTrue(f.message.contains("cut off"))
    }

    @Test fun malformedJsonIsAFailureNotACrash() {
        for (text in listOf("Sorry, I can't.", "{\"summary\":\"x\"", "{{{{", "[1,2]", "```json\n{ nope }\n```")) {
            val f = failed(FakeAiBackend().also { it.answerText(text) })
            assertTrue(text, f.message.contains("could not be read")); assertTrue(f.retryable)
        }
        val cut = failed(FakeAiBackend().also { it.answerText("{\"summary\":\"x\",\"patches\":[{\"path\":\"a", finish = "MAX_TOKENS") })
        assertTrue(cut.message.contains("cut off"))
    }

    @Test fun aGoodAnswerStillWorks() = runBlocking {
        val r = provider(FakeAiBackend().also { it.answerText(goodJson) }).analyze(request) as AiResult.Success
        assertEquals("Missing import", r.response.summary)
    }

    // ---- exceptions that are not AiException (the original crash)
    @Test fun anyUnexpectedExceptionFromTheBackendBecomesAFailureWithItsCause() {
        for (boom in listOf<Throwable>(IllegalStateException("flow"), RuntimeException("x"), IOException("reset"), java.security.GeneralSecurityException("keystore"), SocketTimeoutException("slow"), OutOfMemoryError("heap"))) {
            val b = object : AiBackend by FakeAiBackend() {
                override suspend fun complete(request: AiRequest): AiCompletion = throw boom
                override fun stream(request: AiRequest) = kotlinx.coroutines.flow.flow<AiStreamEvent> { throw boom }
            }
            val f = failed(b)
            assertTrue(f.message.isNotBlank())
            assertEquals(boom, f.cause)
        }
    }

    @Test fun timeoutsHaveATimeoutMessage() {
        val b = object : AiBackend by FakeAiBackend() {
            override fun stream(request: AiRequest) = kotlinx.coroutines.flow.flow<AiStreamEvent> { throw SocketTimeoutException("read timed out") }
        }
        val f = failed(b)
        assertTrue(f.message.contains("in time")); assertTrue(f.retryable)
    }

    @Test fun cancellationIsRethrown() {
        val b = object : AiBackend by FakeAiBackend() {
            override fun stream(request: AiRequest) = kotlinx.coroutines.flow.flow<AiStreamEvent> { throw CancellationException("stop") }
        }
        try { runBlocking { provider(b).analyze(request) }; fail("must propagate") } catch (e: CancellationException) { /* expected */ }
    }

    @Test fun otherErrorsThatMeanABrokenAppAreNotHidden() {
        val b = object : AiBackend by FakeAiBackend() {
            override fun stream(request: AiRequest) = kotlinx.coroutines.flow.flow<AiStreamEvent> { throw NoClassDefFoundError("Broken") }
        }
        try { runBlocking { provider(b).analyze(request) }; fail() } catch (e: NoClassDefFoundError) { /* expected */ }
    }

    // ---- AiException kinds (HTTP errors arrive here already mapped by the backend client)
    @Test fun httpStyleBackendErrorsKeepTheirMessageAndRetryFlag() {
        val cases = listOf(
            aiError(AiErrorKind.RATE_LIMITED, "Too many requests.", true),
            aiError(AiErrorKind.UNAVAILABLE, "The AI backend is not available right now (HTTP 503). Try again later.", true),
            aiError(AiErrorKind.TIMEOUT, "The AI backend did not answer in time (HTTP 504). Try again.", true),
            aiError(AiErrorKind.UNAUTHORIZED, "Sign-in to the AI backend failed. Check your access code in AI settings.", false),
            aiError(AiErrorKind.TOO_LARGE, "The request is too large. Send fewer or smaller files.", false),
            aiError(AiErrorKind.BAD_RESPONSE, "The AI backend sent an answer the app does not understand.", false),
        )
        for (e in cases) {
            val f = failed(FakeAiBackend().also { it.answer(FakeAiBackend.Script.Fail(e)) })
            assertEquals(e.message, f.message.removeSuffix(" Wait about 7 seconds.")); assertEquals(e.retryable, f.retryable)
            assertNull(f.cause)
        }
        val withWait = failed(FakeAiBackend().also { it.answer(FakeAiBackend.Script.Fail(AiException(AiErrorKind.RATE_LIMITED, "Slow down.", true, retryAfterSeconds = 7))) })
        assertTrue(withWait.message.endsWith("Wait about 7 seconds."))
    }

    @Test fun notConfiguredIsUnavailableNotAFailure() = runBlocking {
        val b = FakeAiBackend().also { it.answer(FakeAiBackend.Script.Fail(AiException(AiErrorKind.NOT_CONFIGURED, "Set up AI first"))) }
        assertEquals(AiResult.Unavailable("Set up AI first"), provider(b).analyze(request))
    }

    // ---- the real backend client end to end (HTTP status, malformed SSE, transport errors)
    private class Rig {
        val settings = com.almus.appcreatorpro.ai.AiSettingsStore(InMemoryKeyValueStore(), InMemorySecretStore()).also { it.save("https://ai.example.com/", "alice-access-code-0001") }
        val http = FakeTransport().also { it.on("POST", "/v1/auth/login", jsonResponse(200, """{"token":"t","expiresInSeconds":3600}""")) }
        val streams = FakePostStream()
        val provider = GeminiDebugProvider(GeminiBackendClient(settings, http, streams))
        fun run(vararg r: FakePostStream.Reply): AiResult { streams.reply(*r); return runBlocking { provider.analyze(AiDebugRequest(AiTask.EXPLAIN, "N", "b", true, emptyList(), "log", emptyList())) } }
    }

    @Test fun realClientHttpErrorsAndBrokenStreamsAreFailures() {
        val rig = Rig()
        fun f(r: AiResult) = r as AiResult.Failed
        assertTrue(f(rig.run(FakePostStream.Reply(code = 503, text = "<html>bad gateway</html>"))).message.contains("not available"))
        assertTrue(f(rig.run(FakePostStream.Reply(code = 429, text = "", headers = mapOf("Retry-After" to "9")))).message.contains("Wait about 9"))
        assertTrue(f(rig.run(FakePostStream.Reply(code = 504, text = "<html>timeout</html>"))).message.contains("in time"))
        assertTrue(f(rig.run(FakePostStream.Reply(throws = SocketTimeoutException("t")))).message.contains("in time"))
        assertTrue(f(rig.run(FakePostStream.Reply(throws = IOException("no route")))).message.contains("Could not reach"))
        assertTrue(f(rig.run(FakePostStream.Reply(text = sseOf("meta" to """{"model":"fast"}""", "delta" to """{"text":"{\"summ"}""")))).message.contains("ended before"))
        assertTrue(f(rig.run(FakePostStream.Reply(text = "event: delta\ndata: {not json\n\n"))).message.contains("does not understand"))
        assertTrue(f(rig.run(FakePostStream.Reply(text = sseOf("meta" to """{"model":"fast"}""", "done" to """{"finishReason":"STOP"}""")))).message.contains("empty"))
    }

    @Test fun realClientTransportBugsNoLongerEscape() {
        val rig = Rig()
        val streams = object : com.almus.appcreatorpro.service.ai.PostStreamTransport {
            override suspend fun <T> post(url: String, headers: Map<String, String>, body: String, block: suspend (com.almus.appcreatorpro.service.artifacts.StreamResponse) -> T): T = throw IllegalStateException("transport bug")
        }
        val provider = GeminiDebugProvider(GeminiBackendClient(rig.settings, rig.http, streams))
        val r = runBlocking { provider.analyze(request) } as AiResult.Failed
        assertNotNull(r.cause); assertTrue(r.message.contains("IllegalStateException"))
        assertFalse(r.message.contains("transport bug"))
    }

    @Test fun realClientSuccessfulStreamStillWorks() {
        val rig = Rig()
        val text = goodJson.replace("\"", "\\\"")
        val r = rig.run(FakePostStream.Reply(text = sseOf("meta" to """{"model":"fast","redactions":0}""", "delta" to """{"text":"$text"}""", "done" to """{"finishReason":"STOP"}""")))
        assertEquals("Missing import", (r as AiResult.Success).response.summary)
    }
}
