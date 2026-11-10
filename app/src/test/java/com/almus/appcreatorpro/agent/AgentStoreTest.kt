package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.FakeAiBackend
import com.almus.appcreatorpro.service.ai.FileAgentRunStore
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AgentStoreTest {
    private val goal = "Add a notes feature with a Foo class and a Bar class to the app"
    private val plan = planJson(taskSpec("a", "Create Foo", "code"), taskSpec("b", "Create Bar", "code", dependsOn = listOf("a")))
    private val fooOk = resultJson(createChange(FOO_PATH, fooClass()), summary = "Foo created")
    private val barOk = resultJson(createChange(BAR_PATH, fooClass("Bar")), summary = "Bar created")

    private fun snapshot() = AgentSnapshot(
        runId = "r1", goal = "goal \"quoted\"\nnew line", phase = AgentPhase.AWAITING_REVIEW, stage = AgentStage.REPORT,
        plan = AgentPlan(
            "under", listOf("a1"), "sum", "arch", listOf(AgentDependencyAdvice("a:b:1", "why")),
            listOf(AgentTask("t1", "T", AgentTaskKind.UI, "d", listOf("x.kt"), emptyList(), TaskStatus.DONE, 2, "ok", listOf("x.kt"))), listOf("risk"), "Name", "com.x", "kotlin", listOf("w"),
        ),
        planApproved = true, messages = listOf(AgentMessage(AgentSpeaker.USER, "hi", 5L)), logs = listOf(AgentLogEntry(6L, AgentLogLevel.WARN, "log", "t1")),
        options = AgentOptions(autoCorrect = false), staged = mapOf("x.kt" to "class X\n", "y.kt" to "class Y\n"), baseHashes = mapOf("x.kt" to "abc", "y.kt" to null),
        recommendedDependencies = listOf(AgentDependencyAdvice("c:d:2", "")), rejections = listOf(AgentRejection("z.kt", "no", "t1")),
        corrections = listOf(AgentCorrection(1, listOf("x.kt"), "fixed", 3, 0, false)), reportMarkdown = "# report", modelCalls = 7,
        startedAtMillis = 1L, finishedAtMillis = 9L, error = null, appliedRecordId = null,
    )

    @Test fun `a snapshot survives the JSON round trip with every field`() {
        val s = snapshot()
        assertEquals(s, AgentSnapshotJson.fromJson(AgentSnapshotJson.toJson(s)))
    }

    @Test fun `garbage and other versions are not loaded`() {
        assertNull(AgentSnapshotJson.fromJson("not json"))
        assertNull(AgentSnapshotJson.fromJson("{\"v\":99,\"runId\":\"x\"}"))
        assertNull(AgentSnapshotJson.fromJson("{\"v\":1}"))
    }

    @Test fun `the file store saves, loads and clears one run per project`() = runTest {
        val dir = kotlin.io.path.createTempDirectory("agent-store").toFile()
        try {
            val store = FileAgentRunStore(File(dir, "ai-agent"))
            assertNull(store.load("p1"))
            store.save("p1", snapshot())
            assertEquals(snapshot(), store.load("p1"))
            assertNull(store.load("p2"))
            store.clear("p1")
            assertNull(store.load("p1"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `a finished run comes back after a restart with its files and report`() = runTest {
        val first = AgentRig(this)
        first.backend.answer(FakeAiBackend.Script.Text(plan), FakeAiBackend.Script.Text(fooOk), FakeAiBackend.Script.Text(barOk))
        first.runner.start(goal); advanceUntilIdle()
        first.runner.approvePlan(); advanceUntilIdle()

        val second = AgentRig(this, initial = first.files.files.toMap(), store = first.store)
        second.runner.load()
        val s = second.state
        assertEquals(AgentPhase.AWAITING_REVIEW, s.phase)
        assertEquals(first.state.goal, s.goal)
        assertEquals(setOf(FOO_PATH, BAR_PATH), s.changeSet!!.paths.toSet())
        assertEquals(listOf(TaskStatus.DONE, TaskStatus.DONE), s.tasks.map { it.status })
        assertNotNull(s.report)
        assertTrue(second.files.writes.isEmpty())
        assertTrue(second.runner.applyToProject(null).let { it is com.almus.appcreatorpro.ai.ApplyOutcome.Applied })
    }

    @Test fun `a staged file is dropped on restore when the real file changed in the meantime`() = runTest {
        val p = planJson(taskSpec("a", "Title", "edit", paths = listOf(MAIN_PATH)), taskSpec("b", "Bar", "code"))
        val first = AgentRig(this)
        first.backend.answer(FakeAiBackend.Script.Text(p), FakeAiBackend.Script.Text(resultJson(editChange(MAIN_PATH, "\"Notes\"", "\"Agent\""))), FakeAiBackend.Script.Text(barOk))
        first.runner.start(goal); advanceUntilIdle()
        first.runner.approvePlan(); advanceUntilIdle()
        assertEquals(setOf(MAIN_PATH, BAR_PATH), first.state.changeSet!!.paths.toSet())

        val edited = first.files.files.toMutableMap().also { it[MAIN_PATH] = "package com.example.notes\n\nclass MainActivity { /* user */ }\n" }
        val second = AgentRig(this, initial = edited, store = first.store)
        second.runner.load()
        assertEquals(listOf(BAR_PATH), second.state.changeSet!!.paths)
        assertTrue(second.state.logs.any { it.text.contains("changed in the project") })
        assertEquals("package com.example.notes\n\nclass MainActivity { /* user */ }\n", second.files.files[MAIN_PATH])
    }

    @Test fun `a run that was cut off by closing the app can be resumed`() = runTest {
        val running = snapshot().copy(
            phase = AgentPhase.RUNNING, stage = AgentStage.EXECUTE, staged = emptyMap(), baseHashes = emptyMap(), reportMarkdown = null,
            plan = AgentPlan("u", emptyList(), "s", "", emptyList(), listOf(AgentTask("t1", "Create Foo", AgentTaskKind.CODE, "d", status = TaskStatus.RUNNING, attempts = 1)), emptyList()),
        )
        val store = InMemoryAgentRunStore().also { it.save("p1", running) }
        val rig = AgentRig(this, store = store)
        rig.runner.load()
        assertEquals(AgentPhase.CANCELLED, rig.state.phase)
        assertEquals(TaskStatus.PENDING, rig.task("t1").status)
        assertTrue(rig.state.error!!.contains("closed"))
        rig.backend.answerText(fooOk)
        assertTrue(rig.runner.retry()); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertEquals(listOf(FOO_PATH), rig.state.changeSet!!.paths)
    }

    @Test fun `a failed run is restored as failed and can be retried`() = runTest {
        val first = AgentRig(this)
        first.backend.answer(FakeAiBackend.Script.Text(plan), FakeAiBackend.Script.Text(fooOk), FakeAiBackend.Script.Fail(com.almus.appcreatorpro.ai.AiException(com.almus.appcreatorpro.ai.AiErrorKind.BAD_REQUEST, "refused", false)))
        first.runner.start(goal); advanceUntilIdle()
        first.runner.approvePlan(); advanceUntilIdle()
        assertEquals(AgentPhase.FAILED, first.state.phase)

        val second = AgentRig(this, store = first.store)
        second.runner.load()
        assertEquals(AgentPhase.FAILED, second.state.phase)
        assertEquals(TaskStatus.DONE, second.task("t1").status)
        second.backend.answerText(barOk)
        assertTrue(second.runner.retry()); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_REVIEW, second.state.phase)
        assertEquals(setOf(FOO_PATH, BAR_PATH), second.state.changeSet!!.paths.toSet())
    }

    @Test fun `an applied run is restored as applied`() = runTest {
        val first = AgentRig(this)
        first.backend.answer(FakeAiBackend.Script.Text(plan), FakeAiBackend.Script.Text(fooOk), FakeAiBackend.Script.Text(barOk))
        first.runner.start(goal); advanceUntilIdle()
        first.runner.approvePlan(); advanceUntilIdle()
        first.runner.applyToProject(null)
        val second = AgentRig(this, initial = first.files.files.toMap(), store = first.store)
        second.runner.load()
        assertEquals(AgentPhase.APPLIED, second.state.phase)
        assertNotNull(second.state.report)
        assertFalse(second.state.busy)
    }

    @Test fun `with nothing saved, load leaves the runner idle`() = runTest {
        val rig = AgentRig(this)
        rig.runner.load()
        assertEquals(AgentPhase.IDLE, rig.state.phase)
        assertNull(rig.state.runId)
    }
}
