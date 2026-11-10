package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiErrorKind
import com.almus.appcreatorpro.ai.AiException
import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.AiTaskKind
import com.almus.appcreatorpro.ai.ApplyOutcome
import com.almus.appcreatorpro.ai.FakeAiBackend
import com.almus.appcreatorpro.ai.Parsed
import com.almus.appcreatorpro.ai.UndoOutcome
import com.almus.appcreatorpro.debug.Severity
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

private const val GOAL = "Add a notes feature with a Foo class and a Bar class to the app"

/** Counts the marker "BROKEN" in staged files as errors: lets a test decide exactly how many problems a correction leaves. */
private class MarkerTestRunner : AgentTestRunner {
    override suspend fun run(files: AiProjectFiles, stagedPaths: List<String>): AgentTestOutcome {
        val problems = ArrayList<AgentProblem>()
        for (p in stagedPaths) repeat(files.read(p).split("BROKEN").size - 1) { problems += AgentProblem(p, null, "marker $it", Severity.ERROR, ProblemSource.TEST_CHECK) }
        return AgentTestOutcome(false, "marker runner", emptyList(), 0, problems)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunnerTest {
    private val twoTasks = planJson(taskSpec("a", "Create Foo", "code"), taskSpec("b", "Create Bar", "code", dependsOn = listOf("a")))
    private val fooOk = resultJson(createChange(FOO_PATH, fooClass()), summary = "Foo created")
    private val barOk = resultJson(createChange(BAR_PATH, fooClass("Bar")), summary = "Bar created")

    private suspend fun TestScope.planned(rig: AgentRig, vararg answers: String): AgentRig {
        rig.backend.answer(*answers.map { FakeAiBackend.Script.Text(it) }.toTypedArray())
        assertTrue(rig.runner.start(GOAL))
        advanceUntilIdle()
        return rig
    }

    private suspend fun TestScope.finished(rig: AgentRig, vararg answers: String): AgentRig {
        planned(rig, *answers)
        assertTrue(rig.runner.approvePlan())
        advanceUntilIdle()
        return rig
    }

    // ---- steps 1-3 and the approval gate

    @Test fun `start plans and then waits for approval without running anything`() = runTest {
        val rig = planned(AgentRig(this), twoTasks)
        assertEquals(AgentPhase.AWAITING_APPROVAL, rig.state.phase)
        assertFalse(rig.state.planApproved)
        assertEquals(2, rig.state.tasks.size)
        assertEquals(1, rig.backend.requests.size)
        assertEquals(AiTaskKind.AGENT_PLAN, rig.backend.requests[0].task)
        assertTrue(rig.state.tasks.all { it.status == TaskStatus.PENDING })
        assertTrue(rig.files.writes.isEmpty())
        assertTrue(rig.state.logs.any { it.text.startsWith("Step 3") })
    }

    @Test fun `the plan request carries the goal and the project structure`() = runTest {
        val rig = planned(AgentRig(this), twoTasks)
        val text = rig.backend.requests[0].messages.last().content
        assertTrue(text.contains(GOAL))
        assertTrue(text.contains("app/build.gradle.kts"))
    }

    @Test fun `a goal that is too short is refused without any request`() = runTest {
        val rig = AgentRig(this)
        assertFalse(rig.runner.start("make app"))
        assertNotNull(rig.state.error)
        assertEquals(AgentPhase.IDLE, rig.state.phase)
        assertTrue(rig.backend.requests.isEmpty())
    }

    @Test fun `approve is refused when there is no plan and tasks can be removed before approval`() = runTest {
        val rig = AgentRig(this)
        assertFalse(rig.runner.approvePlan())
        planned(rig, twoTasks)
        rig.runner.removeTask("t1")
        assertEquals(listOf("t2"), rig.state.tasks.map { it.id })
        assertTrue(rig.state.tasks[0].dependsOn.isEmpty())
        rig.runner.removeTask("t2")
        assertFalse(rig.runner.approvePlan())
    }

    @Test fun `a plan can be revised with feedback and the old plan and the note are sent along`() = runTest {
        val rig = planned(AgentRig(this), twoTasks)
        rig.backend.answerText(planJson(taskSpec("x", "Only Foo")))
        assertTrue(rig.runner.revisePlan("Only one class please"))
        advanceUntilIdle()
        assertEquals(1, rig.state.tasks.size)
        assertEquals(AgentPhase.AWAITING_APPROVAL, rig.state.phase)
        val text = rig.backend.requests.last().messages.last().content
        assertTrue(text.contains("PREVIOUS PLAN"))
        assertTrue(text.contains("Only one class please"))
        assertEquals(2, rig.state.messages.size)
    }

    @Test fun `an unusable plan is asked for again once and then fails`() = runTest {
        val rig = AgentRig(this)
        rig.backend.answer(FakeAiBackend.Script.Text("sorry, no json"), FakeAiBackend.Script.Text(twoTasks))
        rig.runner.start(GOAL); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_APPROVAL, rig.state.phase)
        assertEquals(2, rig.backend.requests.size)
        val second = rig.backend.requests[1].messages
        assertEquals(3, second.size)
        assertTrue(second.last().content.contains("could not be used"))

        val bad = AgentRig(this)
        bad.backend.answerText("still nothing")
        bad.runner.start(GOAL); advanceUntilIdle()
        assertEquals(AgentPhase.FAILED, bad.state.phase)
        assertTrue(bad.state.retryable)
        assertNull(bad.state.plan)
    }

    @Test fun `a planning failure can be retried`() = runTest {
        val rig = AgentRig(this)
        rig.backend.answer(FakeAiBackend.Script.Fail(AiException(AiErrorKind.UNAUTHORIZED, "bad access code", false)), FakeAiBackend.Script.Text(twoTasks))
        rig.runner.start(GOAL); advanceUntilIdle()
        assertEquals(AgentPhase.FAILED, rig.state.phase)
        assertEquals("bad access code", rig.state.error)
        assertTrue(rig.runner.start(GOAL))
        advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_APPROVAL, rig.state.phase)
    }

    // ---- steps 4-10

    @Test fun `an approved plan runs to review with all ten steps and the project untouched`() = runTest {
        val rig = finished(AgentRig(this), twoTasks, fooOk, barOk)
        val s = rig.state
        assertEquals(AgentPhase.AWAITING_REVIEW, s.phase)
        assertEquals(listOf(TaskStatus.DONE, TaskStatus.DONE), s.tasks.map { it.status })
        assertEquals(listOf(FOO_PATH), rig.task("t1").touched)
        assertEquals(setOf(FOO_PATH, BAR_PATH), s.changeSet!!.paths.toSet())
        assertTrue("project must not be written before apply", rig.files.writes.isEmpty())
        for (n in 1..10) assertTrue("step $n logged", s.logs.any { it.text.startsWith("Step $n") })
        assertNotNull(s.report)
        assertEquals(ReportOutcome.READY, s.report!!.outcome)
        assertTrue(s.readiness!!.ready)
        assertEquals(3, s.modelCalls)
        assertFalse(s.testOutcome!!.executed)
    }

    @Test fun `later tasks see earlier tasks' files and the task memory`() = runTest {
        val rig = finished(AgentRig(this), twoTasks, fooOk, barOk)
        val second = rig.backend.requests[2].messages.last().content
        assertTrue(second.contains("FINISHED TASKS"))
        assertTrue(second.contains("Foo created"))
        assertTrue(second.contains(FOO_PATH))
        assertTrue(second.contains("class Foo"))
        assertEquals(AiTaskKind.AGENT_TASK, rig.backend.requests[2].task)
    }

    @Test fun `user notes are sent with the following tasks`() = runTest {
        val rig = planned(AgentRig(this), twoTasks, fooOk, barOk)
        rig.runner.approvePlan()
        rig.runner.sendMessage("Use the name Bar for the second class")
        advanceUntilIdle()
        assertTrue(rig.backend.requests.drop(1).any { it.messages.last().content.contains("Use the name Bar") })
    }

    @Test fun `dependency advice is collected from the plan and from tasks`() = runTest {
        val plan = planJson(taskSpec("a", "Deps", "dependencies"), extra = mapOf("dependencies" to listOf(mapOf("coordinate" to "androidx.room:room-runtime:2.6.1", "reason" to "storage"))))
        val rig = finished(AgentRig(this), plan, resultJson(createChange(FOO_PATH, fooClass()), extra = mapOf("dependencies" to listOf("com.squareup.retrofit2:retrofit:2.11.0"))))
        assertEquals(setOf("androidx.room:room-runtime:2.6.1", "com.squareup.retrofit2:retrofit:2.11.0"), rig.state.recommendedDependencies.map { it.coordinate }.toSet())
        assertTrue(rig.state.report!!.markdown.contains("Recommended dependencies"))
    }

    @Test fun `analyze and explain tasks never change files`() = runTest {
        val plan = planJson(taskSpec("a", "Explain main", "explain", paths = listOf(MAIN_PATH)))
        val rig = finished(AgentRig(this), plan, resultJson(createChange(FOO_PATH, fooClass()), summary = "It shows a title."))
        assertEquals(TaskStatus.DONE, rig.task("t1").status)
        assertEquals("It shows a title.", rig.task("t1").note)
        assertNull(rig.state.changeSet)
        assertEquals(ReportOutcome.NOTHING_TO_APPLY, rig.state.report!!.outcome)
    }

    // ---- tool calling

    @Test fun `the model can read files with tools and then edit them`() = runTest {
        val plan = planJson(taskSpec("a", "Change the title", "edit"))
        val rig = finished(
            AgentRig(this), plan,
            toolCallsJson("read_file" to mapOf("path" to MAIN_PATH)),
            resultJson(editChange(MAIN_PATH, "\"Notes\"", "\"Agent notes\""), summary = "Retitled"),
        )
        assertEquals(TaskStatus.DONE, rig.task("t1").status)
        val toolRound = rig.backend.requests[2].messages
        assertEquals(3, toolRound.size)
        assertTrue(toolRound.last().content.contains("TOOL RESULTS"))
        assertTrue(rig.state.changeSet!!.changes.single().after.contains("Agent notes"))
        assertTrue(rig.state.logs.any { it.text.startsWith("Tool read_file") })
    }

    @Test fun `a model that keeps asking for tools is stopped`() = runTest {
        val rig = finished(AgentRig(this, perms = AgentPermissions(maxToolRoundsPerTask = 2)), planJson(taskSpec("a", "Loop", "edit")), toolCallsJson("list_files" to emptyMap()))
        assertEquals(AgentPhase.FAILED, rig.state.phase)
        assertTrue(rig.state.error!!.contains("kept asking"))
    }

    // ---- safety

    @Test fun `overwriting a file the model was not shown fails the task and changes nothing`() = runTest {
        val plan = planJson(taskSpec("a", "Tweak it", "edit", detail = "tweak it"))
        val rig = finished(AgentRig(this), plan, resultJson(modifyChange("settings.gradle.kts", "rootProject.name = \"Hacked\"\n")))
        assertEquals(AgentPhase.FAILED, rig.state.phase)
        assertTrue(rig.task("t1").note.contains("not shown"))
        assertTrue(rig.files.writes.isEmpty())
        assertNull(rig.state.changeSet)
    }

    @Test fun `deletes and secrets are rejected, the rest is kept, and the report lists what was refused`() = runTest {
        val plan = planJson(taskSpec("a", "Mixed", "code"))
        val answer = resultJson(createChange(FOO_PATH, fooClass()), deleteChange(MAIN_PATH), createChange(BAR_PATH, "val k = \"AIzaSyA1234567890abcdefghijklmnopqrstuv\""))
        val rig = finished(AgentRig(this), plan, answer)
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertEquals(listOf(FOO_PATH), rig.state.changeSet!!.paths)
        assertEquals(2, rig.state.rejections.size)
        assertTrue(rig.state.report!!.markdown.contains("not allowed to make"))
        assertTrue(rig.files.files.containsKey(MAIN_PATH))
    }

    @Test fun `secrets are never shown in the log or the live output`() = runTest {
        val plan = planJson(taskSpec("a", "Secret", "code"))
        val rig = finished(AgentRig(this), plan, resultJson(createChange(BAR_PATH, "val k = \"AIzaSyA1234567890abcdefghijklmnopqrstuv\"")))
        assertTrue(rig.state.logs.none { it.text.contains("AIzaSyA1234567890") })
        assertFalse(rig.state.liveTail.contains("AIzaSyA1234567890"))
        assertTrue(rig.backend.requests.all { r -> r.messages.none { it.content.contains("AIzaSyA1234567890") } })
    }

    @Test fun `paths that clash with folders or break the file rules never reach the result`() = runTest {
        val plan = planJson(taskSpec("a", "Clash", "code"))
        val answer = resultJson(createChange(FOO_PATH, fooClass()), createChange("app/src/main/java/com/example/notes", "x"))
        val rig = finished(AgentRig(this), plan, answer)
        assertTrue(rig.state.changeSet!!.paths.none { it == "app/src/main/java/com/example/notes" })
    }

    // ---- failure, retry, skip, cancel, budget

    @Test fun `a failed task stops the run, and retry continues at that task without redoing finished ones`() = runTest {
        val rig = AgentRig(this)
        rig.backend.answer(
            FakeAiBackend.Script.Text(twoTasks), FakeAiBackend.Script.Text(fooOk),
            FakeAiBackend.Script.Fail(AiException(AiErrorKind.UNAUTHORIZED, "access code rejected", false)), FakeAiBackend.Script.Text(barOk),
        )
        rig.runner.start(GOAL); advanceUntilIdle()
        rig.runner.approvePlan(); advanceUntilIdle()
        assertEquals(AgentPhase.FAILED, rig.state.phase)
        assertEquals(TaskStatus.DONE, rig.task("t1").status)
        assertEquals(TaskStatus.FAILED, rig.task("t2").status)
        assertTrue(rig.state.error!!.contains("access code rejected"))
        assertTrue(rig.files.writes.isEmpty())

        assertTrue(rig.runner.retry()); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertEquals(2, rig.task("t2").attempts)
        assertEquals(1, rig.task("t1").attempts)
        assertEquals(4, rig.backend.requests.size)
        assertTrue(rig.backend.requests[3].messages.last().content.contains("PREVIOUS ATTEMPT"))
    }

    @Test fun `skipping a failed task skips the tasks that depend on it and finishes`() = runTest {
        val plan = planJson(taskSpec("a", "Foo", "code"), taskSpec("b", "Bar", "code", dependsOn = listOf("a")), taskSpec("c", "Other", "code"))
        val rig = AgentRig(this)
        rig.backend.answer(
            FakeAiBackend.Script.Text(plan), FakeAiBackend.Script.Fail(AiException(AiErrorKind.BAD_REQUEST, "refused", false)),
            FakeAiBackend.Script.Text(resultJson(createChange(BAR_PATH, fooClass("Bar")))),
        )
        rig.runner.start(GOAL); advanceUntilIdle()
        rig.runner.approvePlan(); advanceUntilIdle()
        assertEquals(AgentPhase.FAILED, rig.state.phase)
        assertTrue(rig.runner.skipFailedTasks()); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertEquals(listOf(TaskStatus.SKIPPED, TaskStatus.SKIPPED, TaskStatus.DONE), rig.state.tasks.map { it.status })
        assertEquals(ReportOutcome.NEEDS_ATTENTION, rig.state.report!!.outcome)
    }

    @Test fun `cancel stops at once, keeps the work, shows the live output and can be resumed`() = runTest {
        val rig = AgentRig(this)
        rig.backend.answer(FakeAiBackend.Script.Text(twoTasks), FakeAiBackend.Script.Text(fooOk), FakeAiBackend.Script.Hang("{\"summary\":\"par"), FakeAiBackend.Script.Text(barOk))
        rig.runner.start(GOAL); advanceUntilIdle()
        rig.runner.approvePlan(); advanceUntilIdle()
        assertEquals(AgentPhase.RUNNING, rig.state.phase)
        assertEquals("t2", rig.state.currentTaskId)
        assertTrue(rig.state.liveTail.contains("par"))
        assertTrue(rig.state.busy)

        rig.runner.cancel(); advanceUntilIdle()
        assertEquals(AgentPhase.CANCELLED, rig.state.phase)
        assertEquals(TaskStatus.PENDING, rig.task("t2").status)
        assertEquals(TaskStatus.DONE, rig.task("t1").status)
        assertTrue(rig.files.writes.isEmpty())

        assertTrue(rig.runner.retry()); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertEquals(setOf(FOO_PATH, BAR_PATH), rig.state.changeSet!!.paths.toSet())
    }

    @Test fun `cancelling while planning ends in a state where a new run can start`() = runTest {
        val rig = AgentRig(this)
        rig.backend.answer(FakeAiBackend.Script.Hang("{\"under"), FakeAiBackend.Script.Text(twoTasks))
        rig.runner.start(GOAL); advanceUntilIdle()
        assertEquals(AgentPhase.PLANNING, rig.state.phase)
        rig.runner.cancel(); advanceUntilIdle()
        assertEquals(AgentPhase.CANCELLED, rig.state.phase)
        assertTrue(rig.runner.start(GOAL)); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_APPROVAL, rig.state.phase)
    }

    @Test fun `the number of AI requests per run is limited`() = runTest {
        val rig = AgentRig(this, perms = AgentPermissions(maxModelCalls = 2))
        rig.backend.answer(FakeAiBackend.Script.Text(twoTasks), FakeAiBackend.Script.Text(fooOk), FakeAiBackend.Script.Text(barOk))
        rig.runner.start(GOAL); advanceUntilIdle()
        rig.runner.approvePlan(); advanceUntilIdle()
        assertEquals(AgentPhase.FAILED, rig.state.phase)
        assertFalse(rig.state.retryable)
        assertTrue(rig.state.error!!.contains("limit"))
        assertEquals(2, rig.backend.requests.size)
    }

    @Test fun `an answer that was cut off is asked for again with a hint to send fewer files`() = runTest {
        val rig = AgentRig(this)
        rig.backend.answer(FakeAiBackend.Script.Text(planJson(taskSpec("a", "Foo"))), FakeAiBackend.Script.Text("{\"summary\":\"x\",\"changes\":[{\"path\":", "MAX_TOKENS"), FakeAiBackend.Script.Text(fooOk))
        rig.runner.start(GOAL); advanceUntilIdle()
        rig.runner.approvePlan(); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertTrue(rig.backend.requests[2].messages.last().content.contains("cut off"))
    }

    // ---- steps 5-8

    @Test fun `problems are found, corrected in the staging area, and the project is still untouched`() = runTest {
        val rig = finished(AgentRig(this), planJson(taskSpec("a", "Foo")), resultJson(createChange(FOO_PATH, BAD_KOTLIN)), resultJson(modifyChange(FOO_PATH, fooClass()), summary = "Closed the bracket"))
        val s = rig.state
        assertEquals(AgentPhase.AWAITING_REVIEW, s.phase)
        assertEquals(AiTaskKind.AGENT_FIX, rig.backend.requests.last().task)
        assertTrue(rig.backend.requests.last().messages.last().content.contains("PROBLEMS FOUND"))
        val c = s.corrections.single()
        assertTrue(c.errorsBefore > 0)
        assertEquals(0, c.errorsAfter)
        assertFalse(c.undone)
        assertEquals(fooClass(), s.changeSet!!.changes.single().after)
        assertTrue(s.validation!!.errors.isEmpty())
        assertTrue(rig.files.writes.isEmpty())
    }

    @Test fun `a correction that makes things worse is undone`() = runTest {
        val rig = finished(
            AgentRig(this, testRunner = MarkerTestRunner()), planJson(taskSpec("a", "Foo")),
            resultJson(createChange(FOO_PATH, fooClass() + "// BROKEN\n")),
            resultJson(modifyChange(FOO_PATH, fooClass() + "// BROKEN BROKEN BROKEN\n"), summary = "oops"),
        )
        val c = rig.state.corrections.single()
        assertTrue(c.undone)
        assertEquals(1, c.errorsBefore)
        assertEquals(3, c.errorsAfter)
        assertEquals(fooClass() + "// BROKEN\n", rig.state.changeSet!!.changes.single().after)
        assertEquals(ReportOutcome.NEEDS_ATTENTION, rig.state.report!!.outcome)
        assertEquals(3, rig.backend.requests.size)
    }

    @Test fun `corrections stop after the configured number of rounds`() = runTest {
        val rig = finished(
            AgentRig(this, perms = AgentPermissions(maxCorrectionRounds = 2), testRunner = MarkerTestRunner()), planJson(taskSpec("a", "Foo")),
            resultJson(createChange(FOO_PATH, fooClass() + "// BROKEN BROKEN\n")),
            resultJson(modifyChange(FOO_PATH, fooClass() + "// BROKEN\n")),
            resultJson(modifyChange(FOO_PATH, fooClass() + "// BROKEN 2\n// BROKEN\n"), summary = "x"),
        )
        assertEquals(2, rig.state.corrections.size)
        assertTrue(rig.backend.requests.count { it.task == AiTaskKind.AGENT_FIX } <= 2)
    }

    @Test fun `with auto-correct off the agent only reports, and fix problems runs the correction on request`() = runTest {
        val rig = AgentRig(this)
        rig.runner.setAutoCorrect(false)
        rig.backend.answer(FakeAiBackend.Script.Text(planJson(taskSpec("a", "Foo"))), FakeAiBackend.Script.Text(resultJson(createChange(FOO_PATH, BAD_KOTLIN))), FakeAiBackend.Script.Text(resultJson(modifyChange(FOO_PATH, fooClass()))))
        rig.runner.start(GOAL, rig.state.options); advanceUntilIdle()
        rig.runner.approvePlan(); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertTrue(rig.state.validation!!.errors.isNotEmpty())
        assertEquals(ReportOutcome.NEEDS_ATTENTION, rig.state.report!!.outcome)
        assertEquals(2, rig.backend.requests.size)

        assertTrue(rig.runner.fixProblems()); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertTrue(rig.state.validation!!.errors.isEmpty())
        assertEquals(ReportOutcome.READY, rig.state.report!!.outcome)
    }

    // ---- apply and roll back

    @Test fun `apply writes through the change manager once and rollback restores the project`() = runTest {
        val rig = finished(AgentRig(this), planJson(taskSpec("a", "Title", "edit", paths = listOf(MAIN_PATH)), taskSpec("b", "Bar", "code")), resultJson(editChange(MAIN_PATH, "\"Notes\"", "\"Agent notes\"")), barOk)
        assertTrue(rig.files.writes.isEmpty())
        val outcome = rig.runner.applyToProject(null)
        assertTrue(outcome is ApplyOutcome.Applied)
        assertEquals(AgentPhase.APPLIED, rig.state.phase)
        assertTrue(rig.files.files[MAIN_PATH]!!.contains("Agent notes"))
        assertEquals(fooClass("Bar"), rig.files.files[BAR_PATH])
        assertNotNull(rig.state.appliedRecordId)
        assertEquals(1, rig.changes.history.value.size)

        val undo = rig.runner.rollbackApplied()
        assertTrue(undo is UndoOutcome.Undone)
        assertEquals(MAIN_TEXT, rig.files.files[MAIN_PATH])
        assertFalse(rig.files.files.containsKey(BAR_PATH))
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertNull(rig.state.appliedRecordId)
    }

    @Test fun `only the selected files are applied`() = runTest {
        val rig = finished(AgentRig(this), twoTasks, fooOk, barOk)
        assertTrue(rig.runner.applyToProject(setOf(FOO_PATH)) is ApplyOutcome.Applied)
        assertTrue(rig.files.files.containsKey(FOO_PATH))
        assertFalse(rig.files.files.containsKey(BAR_PATH))
    }

    @Test fun `apply is refused when a file changed in the project meanwhile, and nothing is written`() = runTest {
        val rig = finished(AgentRig(this), planJson(taskSpec("a", "Title", "edit", paths = listOf(MAIN_PATH))), resultJson(editChange(MAIN_PATH, "\"Notes\"", "\"Agent notes\"")))
        rig.files.files[MAIN_PATH] = "package com.example.notes\n\nclass MainActivity { /* edited by the user */ }\n"
        val outcome = rig.runner.applyToProject(null)
        assertTrue(outcome is ApplyOutcome.Stale)
        assertTrue(rig.files.writes.isEmpty())
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertTrue(rig.files.files[MAIN_PATH]!!.contains("edited by the user"))
    }

    @Test fun `apply is refused while the agent works or before there is a result`() = runTest {
        val rig = AgentRig(this)
        assertTrue(rig.runner.applyToProject(null) is ApplyOutcome.Invalid)
        planned(rig, twoTasks)
        assertTrue(rig.runner.applyToProject(null) is ApplyOutcome.Invalid)
    }

    // ---- discard and restarting

    @Test fun `a run with a result cannot be replaced by accident, discard clears it, then a new run can start`() = runTest {
        val rig = finished(AgentRig(this), twoTasks, fooOk, barOk)
        assertFalse(rig.runner.start(GOAL))
        assertTrue(rig.state.hasUnappliedWork)
        rig.runner.discardRun(); advanceUntilIdle()
        assertEquals(AgentPhase.IDLE, rig.state.phase)
        assertNull(rig.state.changeSet)
        assertNull(rig.store.load("p1"))
        rig.backend.answerText(twoTasks)
        assertTrue(rig.runner.start(GOAL)); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_APPROVAL, rig.state.phase)
    }

    @Test fun `after applying, a new run may start`() = runTest {
        val rig = finished(AgentRig(this), twoTasks, fooOk, barOk)
        rig.runner.applyToProject(null)
        rig.backend.answerText(twoTasks)
        assertTrue(rig.runner.start(GOAL)); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_APPROVAL, rig.state.phase)
        assertNull(rig.state.changeSet)
    }

    // ---- new project: plan already approved

    @Test fun `startWithPlan skips the approval because the user approved when creating the project`() = runTest {
        val rig = AgentRig(this)
        val plan = (AgentPlanParser.parse(planJson(taskSpec("a", "Foo")), false) as Parsed.Ok).value
        rig.backend.answerText(fooOk)
        assertTrue(rig.runner.startWithPlan(GOAL, plan)); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_REVIEW, rig.state.phase)
        assertTrue(rig.state.planApproved)
        assertEquals(1, rig.backend.requests.size)
        assertEquals(AiTaskKind.AGENT_TASK, rig.backend.requests[0].task)
    }
}
