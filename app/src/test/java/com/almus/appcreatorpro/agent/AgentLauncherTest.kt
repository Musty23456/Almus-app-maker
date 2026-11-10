package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiProviderRegistry
import com.almus.appcreatorpro.ai.FakeAiBackend
import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLauncherTest {
    private val description = "Create an offline notes app with search, dark mode and local storage."
    private val extra = mapOf("project_name" to "NotesApp", "package_name" to "com.example.notes", "language" to "kotlin")
    private val newPlan = planJson(taskSpec("a", "Project structure", "scaffold"), taskSpec("b", "Notes screen", "ui", dependsOn = listOf("a")), extra = extra)

    private class Started(val id: String, val goal: String, val plan: AgentPlan)

    private fun TestScope.launcher(backend: FakeAiBackend, projects: FakeProjects, started: MutableList<Started>) =
        AgentProjectLauncher(backend, projects, { id, goal, plan -> started += Started(id, goal, plan) }, this)

    @Test fun `planning creates nothing`() = runTest {
        val backend = FakeAiBackend().apply { answerText(newPlan) }
        val projects = FakeProjects(); val started = mutableListOf<Started>()
        val l = launcher(backend, projects, started)
        l.plan(description); advanceUntilIdle()
        val f = l.flow.value as LauncherFlow.PlanReady
        assertEquals("NotesApp", f.plan.projectName)
        assertEquals("com.example.notes", f.plan.packageName)
        assertEquals(2, f.plan.tasks.size)
        assertTrue(projects.created.isEmpty())
        assertTrue(started.isEmpty())
        assertTrue(backend.requests[0].messages.last().content.contains("NEW, empty project"))
    }

    @Test fun `approving creates the project and starts the agent with the approved plan`() = runTest {
        val backend = FakeAiBackend().apply { answerText(newPlan) }
        val projects = FakeProjects(); val started = mutableListOf<Started>()
        val l = launcher(backend, projects, started)
        l.plan(description); advanceUntilIdle()
        l.removeTask("t2")
        l.approveAndCreate("NotesApp", "com.example.notes"); advanceUntilIdle()
        val created = l.flow.value as LauncherFlow.Created
        assertEquals("proj1", created.projectId)
        assertEquals(ProjectType.ANDROID_KOTLIN, projects.created.single().type)
        assertEquals("com.example.notes", projects.created.single().packageName)
        assertEquals(1, started.size)
        assertEquals("proj1", started[0].id)
        assertEquals(description, started[0].goal)
        assertEquals(listOf("t1"), started[0].plan.tasks.map { it.id })
    }

    @Test fun `a java plan creates a java project`() = runTest {
        val backend = FakeAiBackend().apply { answerText(planJson(taskSpec("a", "x"), extra = extra + ("language" to "java"))) }
        val projects = FakeProjects()
        val l = launcher(backend, projects, mutableListOf())
        l.plan(description); advanceUntilIdle()
        l.approveAndCreate("NotesApp", "com.example.notes"); advanceUntilIdle()
        assertEquals(ProjectType.ANDROID_JAVA, projects.created.single().type)
    }

    @Test fun `a project that cannot be created does not start the agent`() = runTest {
        val backend = FakeAiBackend().apply { answerText(newPlan) }
        val projects = FakeProjects().apply { failWith = IllegalStateException("storage full") }
        val started = mutableListOf<Started>()
        val l = launcher(backend, projects, started)
        l.plan(description); advanceUntilIdle()
        l.approveAndCreate("NotesApp", "com.example.notes"); advanceUntilIdle()
        val f = l.flow.value as LauncherFlow.Failed
        assertEquals("storage full", f.message)
        assertTrue(started.isEmpty())
    }

    @Test fun `a short description and a failing plan are reported`() = runTest {
        val backend = FakeAiBackend().apply { answerText("not a plan") }
        val l = launcher(backend, FakeProjects(), mutableListOf())
        l.plan("app"); advanceUntilIdle()
        assertTrue(l.flow.value is LauncherFlow.Failed)
        assertTrue(backend.requests.isEmpty())
        l.plan(description); advanceUntilIdle()
        assertTrue(l.flow.value is LauncherFlow.Failed)
        assertEquals(2, backend.requests.size)
    }

    @Test fun `cancelling while planning goes back to idle`() = runTest {
        val backend = FakeAiBackend().apply { answer(FakeAiBackend.Script.Hang("{\"under")) }
        val l = launcher(backend, FakeProjects(), mutableListOf())
        l.plan(description); advanceUntilIdle()
        assertTrue(l.flow.value is LauncherFlow.Planning)
        l.cancel(); advanceUntilIdle()
        assertSame(LauncherFlow.Idle, l.flow.value)
    }

    // ---- provider seam

    @Test fun `the registry resolves providers by id and falls back to the default`() {
        val gemini = FakeAiBackend(); val other = FakeAiBackend()
        val reg = AiProviderRegistry(listOf(com.almus.appcreatorpro.ai.SimpleAiProvider("gemini", "Gemini", gemini), com.almus.appcreatorpro.ai.SimpleAiProvider("other", "Other", other)), "gemini")
        assertSame(gemini, reg.default().backend)
        assertSame(other, reg.resolve("other").backend)
        assertSame(gemini, reg.resolve("missing").backend)
        assertSame(gemini, reg.resolve(null).backend)
        assertEquals(2, reg.all().size)
        assertNotNull(reg.get("other"))
        assertFalse(reg.get("nope") != null)
    }

    @Test fun `the registry rejects empty, duplicate and unknown-default setups`() {
        val b = FakeAiBackend()
        fun p(id: String) = com.almus.appcreatorpro.ai.SimpleAiProvider(id, id, b)
        try { AiProviderRegistry(emptyList(), "x"); fail() } catch (e: IllegalArgumentException) { /* expected */ }
        try { AiProviderRegistry(listOf(p("a"), p("a")), "a"); fail() } catch (e: IllegalArgumentException) { /* expected */ }
        try { AiProviderRegistry(listOf(p("a")), "b"); fail() } catch (e: IllegalArgumentException) { /* expected */ }
        assertEquals("gemini", AiProviderRegistry.single(b).default().id)
    }

    @Test fun `the agent runs on any provider that implements the backend interface`() = runTest {
        val other = FakeAiBackend().apply { answerText(planJson(taskSpec("a", "Foo"))) }
        val reg = AiProviderRegistry(listOf(com.almus.appcreatorpro.ai.SimpleAiProvider("other", "Other", other)), "other")
        val rig = AgentRig(this)
        val runner = AgentRunner("p9", "N", ProjectType.ANDROID_KOTLIN, reg.default().backend, rig.files, rig.changes, InMemoryAgentRunStore(), this)
        runner.start("Add a Foo class to the notes application please"); advanceUntilIdle()
        assertEquals(AgentPhase.AWAITING_APPROVAL, runner.state.value.phase)
        assertEquals(1, other.requests.size)
        assertTrue(rig.backend.requests.isEmpty())
    }
}
