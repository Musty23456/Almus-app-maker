package com.almus.appcreatorpro.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTest {
    // ---- markdown
    @Test fun `markdown splits text and code, labels files and tolerates an open fence`() {
        val b = ChatMarkdown.split("Intro text\n\nFile: app/src/Foo.kt\n```kotlin\nclass Foo\n```\nOutro\n```xml\n<a/>")
        assertEquals(4, b.size)
        assertEquals(ChatMarkdown.Block.Text("Intro text"), b[0])
        val c1 = b[1] as ChatMarkdown.Block.Code
        assertEquals("kotlin", c1.language); assertEquals("class Foo", c1.code); assertEquals("app/src/Foo.kt", c1.path); assertTrue(c1.closed)
        assertEquals(ChatMarkdown.Block.Text("Outro"), b[2])
        val c2 = b[3] as ChatMarkdown.Block.Code
        assertEquals("xml", c2.language); assertFalse(c2.closed) // still streaming
        assertEquals(emptyList<ChatMarkdown.Block>(), ChatMarkdown.split(""))
    }

    @Test fun `bold file labels work and prose that mentions a file does not`() {
        assertEquals("a/b.kt", (ChatMarkdown.split("**File:** a/b.kt\n```kotlin\nx\n```")[0] as ChatMarkdown.Block.Code).path)
        assertNull((ChatMarkdown.split("Here is the file: that you wanted\n```kotlin\nx\n```").last() as ChatMarkdown.Block.Code).path)
    }

    // ---- message building
    private fun t(id: String, role: AiRole, text: String, state: TurnState = TurnState.DONE) = ChatTurn(id, role, text, state)

    @Test fun `history drops failed answers, merges same roles and attaches context to the last message only`() {
        val m = ChatMessageBuilder.build(
            listOf(t("1", AiRole.USER, "q1"), t("2", AiRole.MODEL, "a1"), t("3", AiRole.USER, "q2"), t("4", AiRole.MODEL, "", TurnState.ERROR), t("5", AiRole.USER, "q3")),
            latestContext = "FILES: x",
        )
        assertEquals(listOf(AiRole.USER, AiRole.MODEL, AiRole.USER), m.map { it.role })
        assertEquals("q2\n\nq3\n\nFILES: x", m.last().content)
        assertFalse(m.first().content.contains("FILES"))
        assertEquals(AiRole.USER, m.first().role)
    }

    @Test fun `history budget keeps the newest turns and always the question`() {
        val big = "x".repeat(40_000)
        val turns = listOf(t("1", AiRole.USER, big), t("2", AiRole.MODEL, big), t("3", AiRole.USER, "now"))
        val m = ChatMessageBuilder.build(turns, null)
        assertEquals("now", m.last().content); assertEquals(AiRole.USER, m.first().role); assertTrue(m.sumOf { it.content.length } <= ChatMessageBuilder.HISTORY_CHAR_BUDGET + 10)
    }

    // ---- engine
    private fun TestScope.engine(backend: FakeAiBackend, store: ChatHistoryStore = InMemoryChatHistoryStore(), ctx: suspend (List<String>) -> ContextBundle? = { null }): ChatEngine {
        var n = 0
        return ChatEngine("p1", backend, store, ctx, this, clockMillis = { 5L }, newId = { "id${n++}" })
    }

    @Test fun `send streams the answer into the history and saves it`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("Hello **world** with some more text") }
        val store = InMemoryChatHistoryStore()
        val e = engine(backend, store)
        assertTrue(e.send("  hi  ", ChatMode.ASK))
        advanceUntilIdle()
        val s = e.state.value
        assertFalse(s.busy)
        assertEquals(listOf("hi", "Hello **world** with some more text"), s.turns.map { it.text })
        assertEquals(TurnState.DONE, s.turns.last().state); assertEquals("fast", s.turns.last().model)
        assertEquals(AiTaskKind.CHAT, backend.requests.single().task)
        assertEquals(2, store.load("p1").size)
    }

    @Test fun `empty text and a second send while busy are ignored`() = runTest {
        val backend = FakeAiBackend().also { it.answer(FakeAiBackend.Script.Hang("par")) }
        val e = engine(backend)
        assertFalse(e.send("   ", ChatMode.ASK))
        assertTrue(e.send("one", ChatMode.ASK)); advanceUntilIdle()
        assertTrue(e.state.value.busy)
        assertFalse(e.send("two", ChatMode.ASK))
        assertFalse(e.regenerate())
        e.cancel(); advanceUntilIdle()
    }

    @Test fun `cancel keeps the partial answer and marks it stopped`() = runTest {
        val backend = FakeAiBackend().also { it.answer(FakeAiBackend.Script.Hang("partial")) }
        val e = engine(backend)
        e.send("q", ChatMode.ASK); advanceUntilIdle()
        assertEquals("partial", e.state.value.turns.last().text)
        e.cancel(); advanceUntilIdle()
        assertEquals(TurnState.CANCELLED, e.state.value.turns.last().state); assertEquals("partial", e.state.value.turns.last().text)
        assertFalse(e.state.value.busy)
    }

    @Test fun `errors show a message and regenerate retries the same question`() = runTest {
        val backend = FakeAiBackend().also { it.answer(FakeAiBackend.Script.Fail(aiError(message = "Backend down")), FakeAiBackend.Script.Text("fixed answer")) }
        val e = engine(backend)
        e.send("q", ChatMode.EXPLAIN); advanceUntilIdle()
        val failed = e.state.value.turns.last()
        assertEquals(TurnState.ERROR, failed.state); assertEquals("Backend down", failed.error); assertTrue(failed.retryable)
        assertTrue(e.regenerate()); advanceUntilIdle()
        assertEquals(listOf("q", "fixed answer"), e.state.value.turns.map { it.text }) // the failed answer was replaced, not kept
        assertEquals(listOf(AiTaskKind.EXPLAIN, AiTaskKind.EXPLAIN), backend.requests.map { it.task })
    }

    @Test fun `regenerate replaces the last answer and asks again`() = runTest {
        val backend = FakeAiBackend().also { it.answer(FakeAiBackend.Script.Text("first"), FakeAiBackend.Script.Text("second")) }
        val e = engine(backend)
        e.send("q", ChatMode.ASK); advanceUntilIdle(); e.regenerate(); advanceUntilIdle()
        assertEquals(listOf("q", "second"), e.state.value.turns.map { it.text })
        assertEquals(2, backend.requests.size); assertEquals(1, backend.requests[1].messages.size) // the old answer is not sent back
    }

    @Test fun `attached files are sent redacted and only with that question`() = runTest {
        val files = FakeProjectFiles(mapOf("Cfg.kt" to "val key = \"AIzaSyA1234567890abcdefghijklmnopqrstuvw\"\nval x = 1\n", "secrets.xml" to "<s>hunter2hunter2</s>"))
        val builder = ProjectContextBuilder(files)
        val backend = FakeAiBackend().also { it.answerText("ok") }
        val e = engine(backend, ctx = { paths -> builder.build("Proj", null, paths, includeStructure = true) })
        e.send("explain", ChatMode.EXPLAIN, listOf("Cfg.kt", "secrets.xml")); advanceUntilIdle()
        e.send("and now?", ChatMode.ASK); advanceUntilIdle()
        val first = backend.requests[0].messages.last().content
        assertTrue(first.contains("val x = 1")); assertFalse(first.contains("AIzaSyA123")); assertFalse(first.contains("hunter2"))
        val second = backend.requests[1].messages.joinToString("\n") { it.content }
        assertFalse(second.contains("val x = 1")) // old file text is not re-sent
    }

    @Test fun `clear deletes the history`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("a") }
        val store = InMemoryChatHistoryStore(); val e = engine(backend, store)
        e.send("q", ChatMode.ASK); advanceUntilIdle()
        e.clear()
        assertTrue(e.state.value.turns.isEmpty()); assertTrue(store.load("p1").isEmpty())
    }

    @Test fun `history is restored and an unfinished answer becomes stopped`() = runTest {
        val store = InMemoryChatHistoryStore()
        store.save("p1", listOf(ChatTurn("1", AiRole.USER, "q", mode = ChatMode.EDIT, contextPaths = listOf("a.kt")), ChatTurn("2", AiRole.MODEL, "partial", TurnState.STREAMING)))
        val e = engine(FakeAiBackend(), store); e.load()
        assertEquals(2, e.state.value.turns.size); assertEquals(TurnState.CANCELLED, e.state.value.turns[1].state)
        // through the JSON round trip used by the file store
        val back = ChatTurns.fromJson(ChatTurns.toJson(store.load("p1")))
        assertEquals(ChatMode.EDIT, back[0].mode); assertEquals(listOf("a.kt"), back[0].contextPaths); assertEquals(TurnState.CANCELLED, back[1].state)
    }

    @Test fun `a truncated answer shows a notice`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("cut", finish = "MAX_TOKENS") }
        val e = engine(backend); e.send("q", ChatMode.GENERATE); advanceUntilIdle()
        assertNotNull(e.state.value.notice); e.dismissNotice(); assertNull(e.state.value.notice)
    }

    @Test fun `apply to file turns a code block into a reviewable change, never a write`() = runTest {
        val files = FakeProjectFiles(mapOf("a.kt" to "old\n"))
        val turn = ChatTurn("t", AiRole.MODEL, "Done:\n```kotlin\nnew\n```", contextPaths = listOf("a.kt"))
        val r = ChatApply.changeSet(turn, files, 1L)!!
        assertEquals(listOf("a.kt"), r.changeSet!!.paths); assertEquals("old\n", files.files["a.kt"]); assertTrue(files.writes.isEmpty())
        assertNull(ChatApply.changeSet(ChatTurn("t", AiRole.MODEL, "no code here"), files, 1L))
        assertNull(ChatApply.changeSet(ChatTurn("t", AiRole.MODEL, "```kotlin\nx\n```"), files, 1L)) // no file known
    }
}
