package com.almus.appcreatorpro.workspace

import com.almus.appcreatorpro.ai.AiTaskKind
import com.almus.appcreatorpro.ai.FakeAiBackend
import com.almus.appcreatorpro.ai.InMemoryChatHistoryStore
import com.almus.appcreatorpro.ai.TurnState
import com.almus.appcreatorpro.ai.aiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceChatEngineTest {
    private val ws = RepoWorkspace(
        id = "w1", owner = "octocat", repoName = "hello", branch = "main", commitSha = "abcdef1234567", commitTitle = "Init",
        isPrivate = true, defaultBranch = "main", description = null, htmlUrl = "", projectId = "p1",
        importedAtMillis = 1L, lastOpenedAtMillis = 1L, lastSyncedAtMillis = 1L,
    )
    private val plan = "${WorkspacePlans.PLAN_OPEN}\nGoal: better UI\n${WorkspacePlans.PLAN_CLOSE}\nApprove?"

    private fun TestScope.engine(backend: FakeAiBackend, store: InMemoryChatHistoryStore = InMemoryChatHistoryStore()): WorkspaceChatEngine {
        var n = 0
        return WorkspaceChatEngine(
            workspaceId = "w1", backend = backend, history = store,
            promptFor = { _, stage -> WorkspacePromptContext(WorkspacePrompts.instructions(ws, stage), "PROJECT DIGEST: test") },
            scope = this, clockMillis = { 5L }, newId = { "id${n++}" },
        )
    }

    @Test fun `a question carries the rules and the project context, as a chat task`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("I'll inspect the UI. Which screens?") }
        val e = engine(backend)
        assertTrue(e.send("I want to improve the UI"))
        advanceUntilIdle()
        val request = backend.requests.single()
        assertEquals(AiTaskKind.CHAT, request.task)
        val text = request.messages.last().content
        assertTrue(text.contains("You cannot change files"))
        assertTrue(text.contains("octocat/hello"))
        assertTrue(text.contains("PROJECT DIGEST: test"))
        assertTrue(text.contains("I want to improve the UI"))
        assertEquals(WorkspaceStage.DISCUSSING, e.stage)
        assertEquals(TurnState.DONE, e.state.value.turns.last().state)
    }

    @Test fun `approval is possible only after a complete plan and changes nothing but the conversation`() = runTest {
        val backend = FakeAiBackend()
        val e = engine(backend)
        assertFalse(e.approvePlan())

        backend.answerText("Which screens?")
        e.send("improve the UI"); advanceUntilIdle()
        assertFalse(e.approvePlan())
        assertNull(e.approvedPlan())

        backend.answerText(plan)
        e.send("all of them"); advanceUntilIdle()
        assertEquals(WorkspaceStage.PLAN_READY, e.stage)
        assertNull(e.approvedPlan())

        val requestsBefore = backend.requests.size
        assertTrue(e.approvePlan())
        advanceUntilIdle()
        assertEquals(requestsBefore, backend.requests.size) // approving asks the AI nothing
        assertEquals(WorkspaceStage.APPROVED, e.stage)
        assertTrue(e.approvedPlan()!!.contains("better UI"))
        assertFalse(e.approvePlan()) // already approved
    }

    @Test fun `typing the approval sentence by hand does not approve`() = runTest {
        val backend = FakeAiBackend().also { it.answerText(plan) }
        val e = engine(backend)
        e.send("improve the UI"); advanceUntilIdle()
        assertFalse(e.send(WorkspacePlans.APPROVAL_TEXT))
        assertEquals(WorkspaceStage.PLAN_READY, e.stage)
    }

    @Test fun `a new message after approval re-opens the discussion and tells the AI so`() = runTest {
        val backend = FakeAiBackend().also { it.answerText(plan) }
        val e = engine(backend)
        e.send("improve the UI"); advanceUntilIdle()
        e.approvePlan()
        backend.answerText("Sure, which colours?")
        e.send("also add dark mode"); advanceUntilIdle()
        assertEquals(WorkspaceStage.DISCUSSING, e.stage)
        assertNull(e.approvedPlan())
        assertFalse(backend.requests.last().messages.last().content.contains("The user approved the latest plan"))
    }

    @Test fun `a cut-off plan cannot be approved`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("${WorkspacePlans.PLAN_OPEN}\nGoal: half", finish = "MAX_TOKENS") }
        val e = engine(backend)
        e.send("improve the UI"); advanceUntilIdle()
        assertEquals(WorkspaceStage.DISCUSSING, e.stage)
        assertFalse(e.approvePlan())
    }

    @Test fun `errors are shown, retry works and the conversation is saved and reloaded`() = runTest {
        val backend = FakeAiBackend().also { it.answer(FakeAiBackend.Script.Fail(aiError()), FakeAiBackend.Script.Text("Which screens?")) }
        val store = InMemoryChatHistoryStore()
        val e = engine(backend, store)
        e.send("improve the UI"); advanceUntilIdle()
        assertEquals(TurnState.ERROR, e.state.value.turns.last().state)
        assertTrue(e.retry()); advanceUntilIdle()
        assertEquals(TurnState.DONE, e.state.value.turns.last().state)
        assertEquals(2, e.state.value.turns.size)

        val reloaded = engine(FakeAiBackend(), store)
        reloaded.load()
        assertEquals(2, reloaded.state.value.turns.size)
        assertEquals("ws-w1", "ws-" + ws.id) // history is keyed per workspace
    }
}
