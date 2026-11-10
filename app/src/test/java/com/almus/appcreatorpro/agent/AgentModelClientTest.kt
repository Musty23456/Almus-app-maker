package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiErrorKind
import com.almus.appcreatorpro.ai.AiException
import com.almus.appcreatorpro.ai.AiMessage
import com.almus.appcreatorpro.ai.AiRole
import com.almus.appcreatorpro.ai.AiStep
import com.almus.appcreatorpro.ai.AiTaskKind
import com.almus.appcreatorpro.ai.FakeAiBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentModelClientTest {
    private val msgs = listOf(AiMessage(AiRole.USER, "hi"))
    private fun transient(message: String = "down", kind: AiErrorKind = AiErrorKind.UNAVAILABLE, after: Int? = null) = FakeAiBackend.Script.Fail(AiException(kind, message, true, retryAfterSeconds = after))

    @Test fun `a good answer is streamed with progress and a tail`() = runTest {
        val backend = FakeAiBackend().apply { answerText("{\"summary\":\"x\"}") }
        val seen = mutableListOf<Int>()
        val r = AgentModelClient(backend, AgentPermissions()).ask(AiTaskKind.AGENT_TASK, msgs) { n, _ -> seen += n }
        assertEquals("{\"summary\":\"x\"}", (r as AiStep.Ok).value.text)
        assertTrue(seen.size > 1)
        assertEquals(seen.sorted(), seen)
    }

    @Test fun `transient failures are retried with a growing wait and then succeed`() = runTest {
        val backend = FakeAiBackend().apply { answer(transient(), transient(), FakeAiBackend.Script.Text("ok")) }
        val waits = mutableListOf<Long>()
        val client = AgentModelClient(backend, AgentPermissions(), waitMs = { waits += it })
        val r = client.ask(AiTaskKind.AGENT_TASK, msgs)
        assertTrue(r is AiStep.Ok)
        assertEquals(listOf(1_000L, 2_000L), waits)
        assertEquals(3, client.calls)
    }

    @Test fun `the server's retry-after is honoured but capped`() = runTest {
        val backend = FakeAiBackend().apply { answer(transient(kind = AiErrorKind.RATE_LIMITED, after = 7), transient(kind = AiErrorKind.RATE_LIMITED, after = 500), FakeAiBackend.Script.Text("ok")) }
        val waits = mutableListOf<Long>()
        AgentModelClient(backend, AgentPermissions(), waitMs = { waits += it }).ask(AiTaskKind.AGENT_TASK, msgs)
        assertEquals(listOf(7_000L, 30_000L), waits)
    }

    @Test fun `retries stop after the limit and the failure is reported`() = runTest {
        val backend = FakeAiBackend().apply { answer(transient("still down")) }
        val client = AgentModelClient(backend, AgentPermissions(maxTransportRetries = 2), waitMs = {})
        val r = client.ask(AiTaskKind.AGENT_TASK, msgs) as AiStep.Failed
        assertEquals("still down", r.message)
        assertTrue(r.retryable)
        assertEquals(3, client.calls)
    }

    @Test fun `errors that cannot be retried fail at once`() = runTest {
        val backend = FakeAiBackend().apply { answer(FakeAiBackend.Script.Fail(AiException(AiErrorKind.UNAUTHORIZED, "bad code", false))) }
        val client = AgentModelClient(backend, AgentPermissions(), waitMs = { error("must not wait") })
        val r = client.ask(AiTaskKind.AGENT_TASK, msgs) as AiStep.Failed
        assertEquals("bad code", r.message)
        assertEquals(1, client.calls)
    }

    @Test fun `the request budget of a run is enforced`() = runTest {
        val backend = FakeAiBackend().apply { answerText("{}") }
        val client = AgentModelClient(backend, AgentPermissions(maxModelCalls = 2))
        client.ask(AiTaskKind.AGENT_TASK, msgs); client.ask(AiTaskKind.AGENT_TASK, msgs)
        val r = client.ask(AiTaskKind.AGENT_TASK, msgs) as AiStep.Failed
        assertTrue(r.message.contains("limit"))
        assertTrue(!r.retryable)
        assertEquals(2, backend.requests.size)
    }

    @Test fun `cancelling the caller cancels the request`() = runTest {
        val backend = FakeAiBackend().apply { answer(FakeAiBackend.Script.Hang("{\"par")) }
        var finished = false
        val job = launch { AgentModelClient(backend, AgentPermissions()).ask(AiTaskKind.AGENT_TASK, msgs); finished = true }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertTrue(!finished)
    }
}
