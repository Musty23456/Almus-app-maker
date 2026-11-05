package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.service.ai.GeminiBackendClient
import com.almus.appcreatorpro.service.github.InMemorySecretStore
import com.almus.appcreatorpro.testutil.FakeClock
import com.almus.appcreatorpro.testutil.FakeTransport
import com.almus.appcreatorpro.testutil.jsonResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class BackendClientTest {
    private val code = "alice-access-code-0001"
    private val settings = AiSettingsStore(InMemoryKeyValueStore(), InMemorySecretStore()).also {
        assertEquals(AiSettingsStore.SaveResult.Saved, it.save("https://ai.example.com/", code))
    }
    private val http = FakeTransport()
    private val streams = FakePostStream()
    private val clock = FakeClock()
    private val client = GeminiBackendClient(settings, http, streams, clockMillis = { clock.nowMillis })

    private fun login(token: String = "tok1", ttl: Int = 3600) =
        jsonResponse(200, """{"token":"$token","expiresInSeconds":$ttl,"user":"alice"}""")

    private val req = AiRequest(AiTaskKind.CHAT, listOf(AiMessage(AiRole.USER, "hi")))

    @Test fun `not configured is reported before any network call`() = runTest {
        val empty = GeminiBackendClient(AiSettingsStore(InMemoryKeyValueStore(), InMemorySecretStore()), http, streams)
        assertTrue(empty.availability() is AiAvailability.NotReady)
        try { empty.complete(req); fail() } catch (e: AiException) { assertEquals(AiErrorKind.NOT_CONFIGURED, e.kind) }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `availability is ready once address and code are saved`() = runTest {
        assertEquals(AiAvailability.Ready, client.availability())
    }

    @Test fun `complete signs in once, sends the token and never the access code or a Gemini key`() = runTest {
        http.on("POST", "/v1/auth/login", login())
        http.on("POST", "/v1/generate", jsonResponse(200, """{"text":"hello","model":"fast","finishReason":"STOP","usage":{"promptTokens":1,"outputTokens":2,"totalTokens":3},"redactions":2}"""))
        val a = client.complete(req.copy(model = "pro"))
        client.complete(req)
        assertEquals("hello", a.text); assertEquals("STOP", a.finishReason); assertEquals(2, a.redactions); assertEquals(3, a.usage?.totalTokens)
        assertEquals("login only once (token cached)", 1, http.count("POST", "/v1/auth/login"))
        val gen = http.requests.first { it.url.endsWith("/v1/generate") }
        assertEquals("https://ai.example.com/v1/generate", gen.url)
        assertEquals("Bearer tok1", gen.headers["Authorization"])
        assertTrue(gen.body!!.contains("\"task\":\"chat\"") && gen.body!!.contains("\"model\":\"pro\""))
        assertFalse("access code must only go to the login endpoint", gen.body!!.contains(code))
        assertFalse(http.requests.filter { !it.url.endsWith("/login") }.any { it.headers.values.any { v -> v.contains(code) } })
        assertFalse(http.requests.any { it.url.contains("googleapis") || (it.body ?: "").contains("AIza") })
    }

    @Test fun `selected model from settings is sent when the request names none`() = runTest {
        settings.selectModel("pro")
        http.on("POST", "/v1/auth/login", login())
        http.on("POST", "/v1/generate", jsonResponse(200, """{"text":"x"}"""))
        client.complete(req)
        assertTrue(http.requests.last().body!!.contains("\"model\":\"pro\""))
    }

    @Test fun `an expired token is replaced by signing in again`() = runTest {
        http.on("POST", "/v1/auth/login", login("t1", 60), login("t2", 60))
        http.on("POST", "/v1/generate", jsonResponse(200, """{"text":"x"}"""))
        client.complete(req)
        clock.nowMillis += 61_000
        client.complete(req)
        assertEquals(2, http.count("POST", "/v1/auth/login"))
        assertEquals("Bearer t2", http.requests.last().headers["Authorization"])
    }

    @Test fun `unauthorized answer triggers one fresh sign-in and a retry`() = runTest {
        http.on("POST", "/v1/auth/login", login("t1"), login("t2"))
        http.on("POST", "/v1/generate", jsonResponse(401, """{"error":{"code":"unauthorized","message":"expired","retryable":false}}"""), jsonResponse(200, """{"text":"ok"}"""))
        assertEquals("ok", client.complete(req).text)
        assertEquals(2, http.count("POST", "/v1/auth/login"))
        assertEquals(2, http.count("POST", "/v1/generate"))
    }

    @Test fun `a wrong access code is a clear unauthorized error and is not retried forever`() = runTest {
        http.on("POST", "/v1/auth/login", jsonResponse(401, """{"error":{"code":"invalid_access_code","message":"The access code is not correct."}}"""))
        try { client.complete(req); fail() } catch (e: AiException) { assertEquals(AiErrorKind.UNAUTHORIZED, e.kind) }
        assertEquals(1, http.count("POST", "/v1/auth/login"))
    }

    @Test fun `changing the backend address does not reuse the old token`() = runTest {
        http.on("POST", "/v1/auth/login", login("t1"), login("t2"))
        http.on("POST", "/v1/generate", jsonResponse(200, """{"text":"x"}"""))
        client.complete(req)
        settings.save("https://other.example.com", null)
        client.complete(req)
        assertEquals(2, http.count("POST", "/v1/auth/login"))
        assertTrue(http.requests.last().url.startsWith("https://other.example.com/"))
    }

    @Test fun `error answers are mapped to kinds, with retry hints, and never show proxy pages`() = runTest {
        http.on("POST", "/v1/auth/login", login())
        fun err(status: Int, body: String, h: Map<String, String> = emptyMap()) { http.on("POST", "/v1/generate", jsonResponse(status, body, h)) }
        suspend fun kind(): AiException = try { client.complete(req); fail("no error"); error("") } catch (e: AiException) { e }

        err(429, """{"error":{"code":"rate_limited","message":"Slow down","retryable":true,"retryAfterSeconds":12}}""")
        kind().let { assertEquals(AiErrorKind.RATE_LIMITED, it.kind); assertEquals(12, it.retryAfterSeconds); assertTrue(it.retryable) }
        err(503, """{"error":{"code":"upstream_unavailable","message":"busy","retryable":true}}""")
        assertEquals(AiErrorKind.UNAVAILABLE, kind().kind)
        err(504, """{"error":{"code":"upstream_timeout","message":"slow","retryable":true}}""")
        assertEquals(AiErrorKind.TIMEOUT, kind().kind)
        err(422, """{"error":{"code":"blocked","message":"declined"}}""")
        assertEquals(AiErrorKind.BLOCKED, kind().kind)
        err(413, """{"error":{"code":"payload_too_large","message":"too big"}}""")
        assertEquals(AiErrorKind.TOO_LARGE, kind().kind)
        err(502, """{"error":{"code":"upstream_auth","message":"not configured"}}""")
        assertEquals(AiErrorKind.SERVER_CONFIG, kind().kind)
        err(400, """{"error":{"code":"bad_model","message":"Model not allowed"}}""")
        assertEquals(AiErrorKind.BAD_REQUEST, kind().kind)
        err(429, "<html>Too many</html>", mapOf("Retry-After" to "30"))
        kind().let { assertEquals(AiErrorKind.RATE_LIMITED, it.kind); assertEquals(30, it.retryAfterSeconds) }
        err(502, "<html><body>Bad gateway SECRET-PAGE</body></html>")
        kind().let { assertEquals(AiErrorKind.UNAVAILABLE, it.kind); assertFalse(it.message!!.contains("SECRET-PAGE")) }
        err(200, "<html>not json</html>")
        assertEquals(AiErrorKind.BAD_RESPONSE, kind().kind)
    }

    @Test fun `connection problems become network or timeout errors`() = runTest {
        http.on("POST", "/v1/auth/login", IOException("no route"))
        try { client.complete(req); fail() } catch (e: AiException) { assertEquals(AiErrorKind.NETWORK, e.kind); assertTrue(e.retryable) }
        http.on("POST", "/v1/auth/login", login())
        http.on("POST", "/v1/generate", SocketTimeoutException("slow"))
        try { client.complete(req); fail() } catch (e: AiException) { assertEquals(AiErrorKind.TIMEOUT, e.kind) }
    }

    @Test fun `models are listed with the default`() = runTest {
        http.on("POST", "/v1/auth/login", login())
        http.on("GET", "/v1/models", jsonResponse(200, """{"default":"fast","models":[{"id":"fast"},{"id":"pro"}]}"""))
        val m = client.models()
        assertEquals("fast", m.default); assertEquals(listOf("fast", "pro"), m.models.map { it.id })
    }

    // ---------------------------------------------------------------- streaming
    private fun okStream() = sseOf(
        "meta" to """{"model":"fast","redactions":1}""",
        "delta" to """{"text":"Hel"}""", "delta" to """{"text":"lo"}""",
        "done" to """{"finishReason":"STOP","usage":{"promptTokens":1,"outputTokens":2,"totalTokens":3}}""",
    )

    @Test fun `streaming yields started, deltas and finished, with the session token`() = runTest {
        http.on("POST", "/v1/auth/login", login())
        streams.reply(FakePostStream.Reply(text = okStream()))
        val events = client.stream(req).toList()
        assertEquals(AiStreamEvent.Started("fast", 1), events[0])
        assertEquals("Hello", events.filterIsInstance<AiStreamEvent.Delta>().joinToString("") { it.text })
        assertEquals("STOP", (events.last() as AiStreamEvent.Finished).finishReason)
        assertEquals("https://ai.example.com/v1/generate/stream", streams.sent[0].url)
        assertEquals("Bearer tok1", streams.sent[0].headers["Authorization"])
        assertFalse(streams.sent[0].body.contains(code))
    }

    @Test fun `streaming collected into one completion`() = runTest {
        http.on("POST", "/v1/auth/login", login())
        streams.reply(FakePostStream.Reply(text = okStream()))
        val seen = mutableListOf<Int>()
        val c = client.completeStreaming(req) { seen += it }
        assertEquals("Hello", c.text); assertEquals(1, c.redactions); assertEquals(listOf(3, 5), seen); assertFalse(c.truncated)
    }

    @Test fun `an error event ends the stream with a typed error`() = runTest {
        http.on("POST", "/v1/auth/login", login())
        streams.reply(FakePostStream.Reply(text = sseOf("meta" to """{"model":"fast"}""", "delta" to """{"text":"par"}""", "error" to """{"code":"upstream_timeout","message":"interrupted","retryable":true}""")))
        try { client.stream(req).toList(); fail() } catch (e: AiException) { assertEquals(AiErrorKind.TIMEOUT, e.kind); assertTrue(e.retryable) }
    }

    @Test fun `a stream that stops without done is an error, not a silent partial answer`() = runTest {
        http.on("POST", "/v1/auth/login", login())
        streams.reply(FakePostStream.Reply(text = sseOf("meta" to """{"model":"fast"}""", "delta" to """{"text":"half"}""")))
        try { client.completeStreaming(req); fail() } catch (e: AiException) { assertEquals(AiErrorKind.NETWORK, e.kind) }
    }

    @Test fun `http error before streaming is mapped like any other error`() = runTest {
        http.on("POST", "/v1/auth/login", login())
        streams.reply(FakePostStream.Reply(code = 429, text = """{"error":{"code":"rate_limited","message":"wait","retryable":true,"retryAfterSeconds":5}}"""))
        try { client.stream(req).toList(); fail() } catch (e: AiException) { assertEquals(AiErrorKind.RATE_LIMITED, e.kind); assertEquals(5, e.retryAfterSeconds) }
    }

    @Test fun `stream signs in again once when the session was rejected at the start`() = runTest {
        http.on("POST", "/v1/auth/login", login("t1"), login("t2"))
        streams.reply(FakePostStream.Reply(code = 401, text = """{"error":{"code":"unauthorized","message":"expired"}}"""), FakePostStream.Reply(text = okStream()))
        val events = client.stream(req).toList()
        assertTrue(events.last() is AiStreamEvent.Finished)
        assertEquals("Bearer t2", streams.sent[1].headers["Authorization"])
    }

    @Test fun `network failure while streaming is a typed error`() = runTest {
        http.on("POST", "/v1/auth/login", login())
        streams.reply(FakePostStream.Reply(throws = IOException("reset")))
        try { client.stream(req).toList(); fail() } catch (e: AiException) { assertEquals(AiErrorKind.NETWORK, e.kind) }
    }

    @Test fun `no Gemini key or token appears in toString of config or requests`() = runTest {
        assertFalse(settings.config()!!.toString().contains(code))
        http.on("POST", "/v1/auth/login", login())
        http.on("POST", "/v1/generate", jsonResponse(200, """{"text":"x"}"""))
        client.complete(req)
        assertTrue(http.requests.none { it.toString().contains("tok1") || it.toString().contains(code) })
        assertNull(settings.config()?.toString()?.takeIf { it.contains("AIza") })
    }
}
