package com.almus.appcreatorpro.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorAiTest {
    private val code = (1..80).joinToString("\n") { "val line$it = $it" }

    private fun ctx(selStart: Int, selEnd: Int = selStart, path: String = "src/Main.kt", text: String = code) =
        EditorAiContext(path, text, selStart, selEnd, "Notes")

    @Test
    fun sensitive_and_empty_files_are_blocked() {
        assertNotNull(EditorAiPrompts.blockedReason(ctx(0, path = "keystore/release.jks")))
        assertNotNull(EditorAiPrompts.blockedReason(ctx(0, path = ".env")))
        assertNotNull(EditorAiPrompts.blockedReason(ctx(0, text = "  ")))
        assertNull(EditorAiPrompts.blockedReason(ctx(0)))
    }

    @Test
    fun explain_without_selection_marks_the_cursor_and_sends_only_a_window() {
        val offset = code.indexOf("line40")
        val req = EditorAiPrompts.explain(ctx(offset))
        assertEquals(AiTaskKind.EXPLAIN, req.task)
        val text = req.messages.single().content
        assertTrue(text.contains(EditorAiPrompts.CURSOR_MARKER))
        assertTrue(text.contains("FILE: src/Main.kt"))
        assertTrue(text.contains("PROJECT: Notes"))
        assertTrue(text.contains("line40"))
        assertFalse("far away lines must not be sent", text.contains("line1 = 1"))
        assertFalse(text.contains("line80"))
    }

    @Test
    fun explain_with_selection_uses_selection_markers() {
        val a = code.indexOf("line10")
        val req = EditorAiPrompts.explain(ctx(a, a + 12), "why is this here?")
        val text = req.messages.single().content
        assertTrue(text.contains("<SELECTION>"))
        assertTrue(text.contains("</SELECTION>"))
        assertTrue(text.contains("SELECTED"))
        assertTrue(text.contains("why is this here?"))
        assertFalse(text.contains(EditorAiPrompts.CURSOR_MARKER))
    }

    @Test
    fun suggest_chooses_the_task_by_selection() {
        val insert = EditorAiPrompts.suggest(ctx(10), "add a loop")
        assertEquals(AiTaskKind.GENERATE_CODE, insert.task)
        assertTrue(insert.messages.single().content.contains("add a loop"))
        val rewrite = EditorAiPrompts.suggest(ctx(10, 30), null)
        assertEquals(AiTaskKind.EDIT_CODE, rewrite.task)
    }

    @Test
    fun secrets_in_the_code_are_replaced_before_anything_is_sent() {
        val secretCode = "val storePassword = \"hunter2hunter2\"\nfun f() {}\n"
        val req = EditorAiPrompts.explain(ctx(secretCode.length - 3, text = secretCode))
        val text = req.messages.single().content
        assertFalse(text.contains("hunter2hunter2"))
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test
    fun a_huge_selection_is_cut() {
        val big = "x".repeat(EditorAiPrompts.MAX_SELECTION_CHARS * 3)
        val req = EditorAiPrompts.explain(ctx(0, big.length, text = big))
        assertTrue(req.messages.single().content.length < EditorAiPrompts.MAX_SELECTION_CHARS + 2000)
    }

    @Test
    fun declarations_of_the_file_are_listed() {
        val req = EditorAiPrompts.explain(ctx(0, text = "class Foo\nfun bar() {}\n"))
        assertTrue(req.messages.single().content.contains("DECLARATIONS IN THIS FILE: Foo, bar"))
    }

    @Test
    fun extract_code_takes_the_first_complete_block() {
        assertEquals("val a = 1", EditorAiPrompts.extractCode("Here:\n```kotlin\nval a = 1\n```\nbye"))
        assertEquals("a\nb", EditorAiPrompts.extractCode("```\na\nb\n```"))
        assertEquals("one", EditorAiPrompts.extractCode("```js\none\n```\n```js\ntwo\n```"))
    }

    @Test
    fun extract_code_gives_null_for_no_block_empty_block_or_a_cut_off_block() {
        assertNull(EditorAiPrompts.extractCode("just text"))
        assertNull(EditorAiPrompts.extractCode("```kotlin\n```"))
        assertNull(EditorAiPrompts.extractCode("```kotlin\nval a = 1\n"))
    }

    @Test
    fun ask_returns_the_text() = runTest {
        val backend = FakeAiBackend()
        backend.answerText("hello")
        val step = EditorAiPrompts.ask(backend, EditorAiPrompts.explain(ctx(0)))
        assertEquals(AiStep.Ok("hello"), step)
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun ask_reports_truncated_empty_and_failed_answers() = runTest {
        val backend = FakeAiBackend()
        backend.answerText("partial", finish = "MAX_TOKENS")
        assertTrue(EditorAiPrompts.ask(backend, EditorAiPrompts.explain(ctx(0))) is AiStep.Failed)
        backend.answerText("   ")
        assertTrue(EditorAiPrompts.ask(backend, EditorAiPrompts.explain(ctx(0))) is AiStep.Failed)
        backend.answer(FakeAiBackend.Script.Fail(AiException(AiErrorKind.RATE_LIMITED, "slow down", retryable = true)))
        val failed = EditorAiPrompts.ask(backend, EditorAiPrompts.explain(ctx(0))) as AiStep.Failed
        assertEquals("slow down", failed.message)
        assertTrue(failed.retryable)
    }
}
