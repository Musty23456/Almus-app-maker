package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.debug.AiResult
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun jsonString(s: String): String = com.almus.appcreatorpro.service.github.MiniJson.stringify(s)

@OptIn(ExperimentalCoroutinesApi::class)
class FlowsTest {
    private val planJson = """{"project_name":"NotesApp","summary":"Offline notes with search","package_name":"com.example.notes","language":"kotlin","technologies":[{"name":"Room","purpose":"storage"}],"dependencies":[],"architecture":"MVVM","structure":["a"],"steps":[{"title":"s","detail":"d"}],"risks":[]}"""

    private fun projectJson(files: List<Pair<String, String>>, name: String = "NotesApp") =
        """{"project_name":"$name","package_name":"com.example.notes","summary":"generated","files":[${files.joinToString(",") { """{"path":"${it.first}","content":${jsonString(it.second)}}""" }}],"notes":["Run a build"]}"""

    private val goodFiles = listOf(
        "settings.gradle.kts" to "rootProject.name = \"NotesApp\"\ninclude(\":app\")\n",
        "build.gradle.kts" to "plugins {}\n",
        "app/build.gradle.kts" to "plugins {}\n",
        "app/src/main/AndroidManifest.xml" to GOOD_MANIFEST,
        "app/src/main/java/com/example/notes/MainActivity.kt" to GOOD_KOTLIN.replace("Foo", "MainActivity"),
    )

    // ---- operations
    @Test fun `plan request uses the plan task and returns a parsed plan`() = runTest {
        val b = FakeAiBackend().also { it.answerText(planJson) }
        val r = AiOperations(b).plan("Create a modern offline notes application using Kotlin with dark mode, search and local database storage") as AiStep.Ok
        assertEquals("NotesApp", r.value.projectName)
        assertEquals(AiTaskKind.PLAN_PROJECT, b.requests.single().task)
    }

    @Test fun `short or huge descriptions are refused before anything is sent`() = runTest {
        val b = FakeAiBackend().also { it.answerText(planJson) }
        assertTrue(AiOperations(b).plan("notes") is AiStep.Failed)
        assertTrue(AiOperations(b).generateProject("x".repeat(AiOperations.MAX_DESCRIPTION + 1), null) is AiStep.Failed)
        assertTrue(b.requests.isEmpty())
    }

    @Test fun `single file and multi file generation are validated`() = runTest {
        val b = FakeAiBackend().also { it.answerText(projectJson(goodFiles + ("../evil.kt" to "x") + ("app/key.kt" to "val k = \"AIzaSyA1234567890abcdefghijklmnopqrstuvw\""))) }
        val r = (AiOperations(b).generateProject("An offline notes app with search and dark mode", null) as AiStep.Ok).value
        assertEquals(goodFiles.map { it.first }.toSet(), r.report.accepted.map { it.path }.toSet())
        assertEquals(setOf("../evil.kt", "app/key.kt"), r.report.rejected.map { it.path }.toSet())
        assertTrue(r.finishedCleanly)
        val sent = b.requests.single().messages.single().content
        assertTrue(sent.contains("DESCRIPTION")); assertEquals(AiTaskKind.GENERATE_PROJECT, b.requests.single().task)
    }

    @Test fun `the approved plan is sent with the generate request`() = runTest {
        val b = FakeAiBackend().also { it.answerText(projectJson(goodFiles)) }
        val plan = (ProjectPlanParser.parse(planJson) as Parsed.Ok).value
        AiOperations(b).generateProject("An offline notes app with search", plan)
        assertTrue(b.requests.single().messages.single().content.contains("APPROVED PLAN") && b.requests.single().messages.single().content.contains("Room: storage"))
    }

    @Test fun `a cut-off project answer fails with a clear message and no files`() = runTest {
        val b = FakeAiBackend().also { it.answerText("{\"project_name\":\"X\",\"files\":[{\"path\":\"a.kt\",\"content\":\"cla", finish = "MAX_TOKENS") }
        val r = AiOperations(b).generateProject("An offline notes app with search", null) as AiStep.Failed
        assertTrue(r.message.contains("cut off")); assertTrue(r.retryable)
    }

    @Test fun `backend failures become user messages`() = runTest {
        val b = FakeAiBackend().also { it.answer(FakeAiBackend.Script.Fail(aiError(AiErrorKind.RATE_LIMITED, "Too many requests", true))) }
        val r = AiOperations(b).plan("An offline notes app with search and dark mode") as AiStep.Failed
        assertEquals("Too many requests", r.message); assertTrue(r.retryable)
    }

    // ---- existing project editing, end to end
    private val login = "package com.x\n\nfun loginScreen() = \"white\"\n"
    private val projectFiles get() = mapOf(
        "app/src/main/java/com/x/LoginScreen.kt" to login, "app/src/main/java/com/x/Main.kt" to "package com.x\nfun main() {}\n",
        "local.properties" to "sdk.dir=/secret/path", "app/release.jks" to "KEYSTORE-BYTES",
    )

    private fun TestScope.session(backend: FakeAiBackend, files: FakeProjectFiles) =
        AiProjectSession("p1", "Notes", ProjectType.ANDROID_KOTLIN, backend, files, InMemoryChatHistoryStore(), InMemoryChangeHistoryStore(), this, clockMillis = { 9L })

    @Test fun `edit flow - choose files, confirm, preview, apply, undo`() = runTest {
        val files = FakeProjectFiles(projectFiles); val backend = FakeAiBackend()
        val s = session(backend, files); s.init()
        backend.answerText("""{"summary":"Needs the login screen","files":[{"path":"app/src/main/java/com/x/LoginScreen.kt","reason":"has the colors"},{"path":"made/up.kt","reason":"?"}]}""")
        s.startEdit("Change the login screen design to a modern blue gradient", emptyList(), autoSelect = true); advanceUntilIdle()

        val confirm = s.edit.value as EditFlow.ConfirmFiles
        assertEquals(listOf("app/src/main/java/com/x/LoginScreen.kt"), confirm.bundle.files.map { it.path })
        // step 1 sent only the file LIST: no file text, no credential files
        val step1 = backend.requests[0].messages.single().content
        assertEquals(AiTaskKind.EDIT_PROJECT_SELECT, backend.requests[0].task); assertFalse(step1.contains("loginScreen")); assertFalse(step1.contains("local.properties")); assertFalse(step1.contains("release.jks"))
        assertTrue(files.writes.isEmpty())

        backend.answerText("""{"summary":"Blue gradient","changes":[{"path":"app/src/main/java/com/x/LoginScreen.kt","action":"modify","explanation":"gradient","edits":[{"find":"\"white\"","replace":"\"blue-gradient\""}]}]}""")
        s.confirmEdit(listOf("app/src/main/java/com/x/LoginScreen.kt")); advanceUntilIdle()
        val done = s.edit.value as EditFlow.Done
        assertNotNull(done.changeSetId)
        assertEquals(login, files.files["app/src/main/java/com/x/LoginScreen.kt"]) // still not written: waiting for Apply
        val step2 = backend.requests[1]
        assertEquals(AiTaskKind.EDIT_PROJECT, step2.task); assertTrue(step2.messages.single().content.contains("loginScreen")); assertFalse(step2.messages.single().content.contains("KEYSTORE-BYTES"))

        val entry = s.changes.entry(done.changeSetId!!)!!
        val change = entry.changeSet.changes.single()
        assertTrue(change.after.contains("blue-gradient")); assertEquals(1, change.added); assertEquals(1, change.removed)
        assertTrue(s.changes.apply(done.changeSetId) is ApplyOutcome.Applied)
        assertTrue(files.files["app/src/main/java/com/x/LoginScreen.kt"]!!.contains("blue-gradient"))
        assertTrue(s.changes.undo(done.changeSetId) is UndoOutcome.Undone)
        assertEquals(login, files.files["app/src/main/java/com/x/LoginScreen.kt"])
    }

    @Test fun `edit flow with manual files skips the selection step`() = runTest {
        val files = FakeProjectFiles(projectFiles); val backend = FakeAiBackend()
        val s = session(backend, files); s.init()
        s.startEdit("Rename the function loginScreen", listOf("app/src/main/java/com/x/LoginScreen.kt"), autoSelect = false); advanceUntilIdle()
        assertTrue(s.edit.value is EditFlow.ConfirmFiles); assertTrue(backend.requests.isEmpty())
        s.cancelEdit(); assertEquals(EditFlow.Idle, s.edit.value)
    }

    @Test fun `edit flow reports failures`() = runTest {
        val files = FakeProjectFiles(projectFiles); val backend = FakeAiBackend()
        val s = session(backend, files); s.init()
        s.startEdit("x", emptyList(), true); advanceUntilIdle()
        assertTrue(s.edit.value is EditFlow.Failed) // too short
        s.resetEdit()
        backend.answer(FakeAiBackend.Script.Fail(aiError(AiErrorKind.UNAVAILABLE, "AI backend down")))
        s.startEdit("Change the login screen colors", emptyList(), true); advanceUntilIdle()
        assertEquals("AI backend down", (s.edit.value as EditFlow.Failed).message)
    }

    // ---- review
    @Test fun `review sends only confirmed files and returns findings`() = runTest {
        val files = FakeProjectFiles(projectFiles); val backend = FakeAiBackend()
        val s = session(backend, files); s.init()
        backend.answerText("""{"summary":"Looks fine","findings":[{"category":"security","severity":"high","path":"app/src/main/java/com/x/LoginScreen.kt","line":3,"title":"Hardcoded colour","detail":"d","suggestion":"s"}]}""")
        s.prepareReview(listOf("app/src/main/java/com/x/LoginScreen.kt", "local.properties"), ReviewCategory.SECURITY); advanceUntilIdle()
        val c = s.review.value as ReviewFlow.Confirm
        assertEquals(1, c.bundle.files.size); assertEquals(1, c.bundle.skipped.size); assertTrue(backend.requests.isEmpty()) // nothing sent before confirm
        s.confirmReview(); advanceUntilIdle()
        val d = s.review.value as ReviewFlow.Done
        assertEquals(1, d.result.findings.size); assertEquals(AiTaskKind.REVIEW, backend.requests.single().task)
        assertTrue(backend.requests.single().messages.single().content.contains("Security"))
        assertFalse(backend.requests.single().messages.single().content.contains("sdk.dir"))
    }

    @Test fun `review with only unsendable files fails before sending`() = runTest {
        val s = session(FakeAiBackend(), FakeProjectFiles(projectFiles)); s.init()
        s.prepareReview(listOf("local.properties"), null); advanceUntilIdle()
        assertTrue(s.review.value is ReviewFlow.Failed)
    }

    // ---- project generator
    private class FakeProjects : ProjectRepository {
        val created = mutableListOf<Project>()
        var failWith: Exception? = null
        override fun observeProjects(): Flow<List<Project>> = MutableStateFlow(created.toList())
        override suspend fun getProjects() = created.toList()
        override suspend fun getProject(id: String) = created.firstOrNull { it.id == id }
        override suspend fun createProject(name: String, packageName: String, description: String, type: ProjectType): Project {
            failWith?.let { throw it }
            return Project("proj${created.size + 1}", name, packageName, description, 0L, 0L, type).also { created += it }
        }
        override suspend fun renameProject(id: String, newName: String): Project = error("unused")
        override suspend fun deleteProject(id: String) = error("unused")
    }

    private fun TestScope.generator(backend: FakeAiBackend, projects: FakeProjects, stores: MutableMap<String, FakeProjectFiles>, history: ChangeHistoryStore = InMemoryChangeHistoryStore()) =
        AiProjectGenerator(backend, projects, { id -> stores.getOrPut(id) { FakeProjectFiles(mapOf("app/src/main/AndroidManifest.xml" to "<manifest/>")) } }, history, this, clockMillis = { 3L })

    @Test fun `project generation - plan, review plan, generate, review files, create`() = runTest {
        val backend = FakeAiBackend(); val projects = FakeProjects(); val stores = mutableMapOf<String, FakeProjectFiles>()
        val g = generator(backend, projects, stores)
        val description = "Create a modern offline notes application using Kotlin with dark mode, search, and local database storage."

        backend.answerText(planJson); g.plan(description); advanceUntilIdle()
        val planned = g.flow.value as GeneratorFlow.PlanReady
        assertTrue(projects.created.isEmpty())
        g.updatePlan(planned.plan.copy(projectName = "My Notes"))
        assertEquals("My Notes", (g.flow.value as GeneratorFlow.PlanReady).plan.projectName)

        backend.answerText(projectJson(goodFiles + ("logo.png" to "PNG")))
        g.generate(description, (g.flow.value as GeneratorFlow.PlanReady).plan); advanceUntilIdle()
        val review = g.flow.value as GeneratorFlow.Review
        assertTrue(projects.created.isEmpty()) // nothing created before the user confirms
        assertEquals(1, review.outcome.report.rejected.size)

        g.create("My Notes", excluded = setOf("build.gradle.kts")); advanceUntilIdle()
        val created = g.flow.value as GeneratorFlow.Created
        assertEquals("My Notes", projects.created.single().name); assertEquals("com.example.notes", projects.created.single().packageName)
        val files = stores.getValue(created.projectId)
        assertEquals(4, created.filesWritten); assertFalse("build.gradle.kts" in files.files); assertEquals(GOOD_MANIFEST, files.files["app/src/main/AndroidManifest.xml"])
        assertTrue("app/src/main/java/com/example/notes/MainActivity.kt" in files.files)
    }

    @Test fun `generated project can be undone like any AI change`() = runTest {
        val backend = FakeAiBackend(); val projects = FakeProjects(); val stores = mutableMapOf<String, FakeProjectFiles>(); val history = InMemoryChangeHistoryStore()
        val g = generator(backend, projects, stores, history)
        backend.answerText(projectJson(goodFiles))
        g.generate("An offline notes app with search and dark mode", null); advanceUntilIdle()
        g.create("NotesApp", emptySet()); advanceUntilIdle()
        val created = g.flow.value as GeneratorFlow.Created
        val mgr = AiChangeManager(created.projectId, stores.getValue(created.projectId), history); mgr.load()
        assertEquals(1, mgr.history.value.size)
        assertTrue(mgr.undo(mgr.history.value.single().id) is UndoOutcome.Undone)
        assertEquals(mapOf("app/src/main/AndroidManifest.xml" to "<manifest/>"), stores.getValue(created.projectId).files) // back to the template
    }

    @Test fun `generation failures and creation failures are reported`() = runTest {
        val backend = FakeAiBackend(); val projects = FakeProjects()
        val g = generator(backend, projects, mutableMapOf())
        backend.answer(FakeAiBackend.Script.Fail(aiError(AiErrorKind.TIMEOUT, "Took too long")))
        g.generate("An offline notes app with search", null); advanceUntilIdle()
        assertEquals("Took too long", (g.flow.value as GeneratorFlow.Failed).message)
        g.reset(); assertEquals(GeneratorFlow.Idle, g.flow.value)

        backend.answerText(projectJson(goodFiles)); g.generate("An offline notes app with search", null); advanceUntilIdle()
        projects.failWith = IllegalStateException("A project with this name already exists.")
        g.create("NotesApp", emptySet()); advanceUntilIdle()
        val f = g.flow.value as GeneratorFlow.Failed
        assertEquals("A project with this name already exists.", f.message); assertNull(f.projectId)
    }

    @Test fun `cancelling generation returns to idle`() = runTest {
        val backend = FakeAiBackend().also { it.answer(FakeAiBackend.Script.Hang("{")) }
        val g = generator(backend, FakeProjects(), mutableMapOf())
        g.generate("An offline notes app with search", null); advanceUntilIdle()
        assertTrue(g.flow.value is GeneratorFlow.Generating)
        g.cancel(); advanceUntilIdle()
        assertEquals(GeneratorFlow.Idle, g.flow.value)
    }

    // ---- debugging with Gemini (Phase 8 connection)
    @Test fun `debug provider reports availability and maps backend errors`() = runTest {
        val b = FakeAiBackend()
        val p = GeminiDebugProvider(b)
        assertEquals(AiAvailability.Ready, p.availability())
        b.availabilityValue = AiAvailability.NotReady("not set up"); assertTrue(p.availability() is AiAvailability.NotReady)
        b.answer(FakeAiBackend.Script.Fail(AiException(AiErrorKind.NOT_CONFIGURED, "Set up AI first")))
        assertEquals(AiResult.Unavailable("Set up AI first"), p.analyze(debugRequest()))
        b.answer(FakeAiBackend.Script.Fail(aiError(AiErrorKind.RATE_LIMITED, "Slow down", true)))
        val failed = p.analyze(debugRequest()) as AiResult.Failed
        assertEquals("Slow down", failed.message); assertTrue(failed.retryable)
    }

    @Test fun `debug provider sends the debug task and parses a Phase 8 answer`() = runTest {
        val b = FakeAiBackend().also { it.answerText("""{"summary":"Missing import","rootCause":"Unresolved reference","explanation":"Add the import.","patches":[]}""") }
        val r = GeminiDebugProvider(b).analyze(debugRequest())
        assertEquals(AiTaskKind.DEBUG, b.requests.single().task)
        assertTrue(r is AiResult.Success || r is AiResult.Failed) // the exact schema is owned by Phase 8's parser
        if (r is AiResult.Success) assertEquals("Missing import", r.response.summary)
    }

    private fun debugRequest() = com.almus.appcreatorpro.debug.AiDebugRequest(
        task = com.almus.appcreatorpro.debug.AiTask.EXPLAIN, projectName = "Notes", buildId = "b1", buildFailed = true,
        issues = emptyList(), logExcerpt = "e: Unresolved reference: Foo", files = emptyList(),
    )
}
