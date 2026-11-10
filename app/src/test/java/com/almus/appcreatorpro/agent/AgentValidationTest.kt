package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.ChangeKind
import com.almus.appcreatorpro.ai.FakeProjectFiles
import com.almus.appcreatorpro.debug.Severity
import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentValidationTest {
    private val testPath = "app/src/test/java/com/example/notes/FooTest.kt"
    private val goodTest = "package com.example.notes\n\nimport org.junit.Test\n\nclass FooTest {\n    @Test fun works() {}\n    @Test fun alsoWorks() {}\n}\n"

    @Test fun `valid staged files give no errors`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        staged.write(FOO_PATH, fooClass())
        val v = AgentValidator().validate(staged)
        assertTrue(v.errors.toString(), v.errors.isEmpty())
        assertTrue(v.rejectedPaths.isEmpty())
    }

    @Test fun `a code error in a staged file is reported with its path and line`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        staged.write(FOO_PATH, BAD_KOTLIN)
        val v = AgentValidator().validate(staged)
        val e = v.errors.first { it.path == FOO_PATH }
        assertEquals(ProblemSource.CODE_CHECK, e.source)
        assertTrue(e.line != null)
    }

    @Test fun `problems in files the agent did not touch are not reported`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject() + (BAR_PATH to BAD_KOTLIN)))
        staged.write(FOO_PATH, fooClass())
        assertTrue(AgentValidator().validate(staged).errors.none { it.path == BAR_PATH })
    }

    @Test fun `the whole project is checked, so a reference to an existing class resolves`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        staged.write(FOO_PATH, "package com.example.notes\n\nclass Foo {\n    val main = MainActivity()\n}\n")
        assertTrue(AgentValidator().validate(staged).errors.isEmpty())
    }

    @Test fun `nothing staged means nothing to validate`() = runTest {
        val v = AgentValidator().validate(StagedProjectFiles(FakeProjectFiles(sampleProject())))
        assertTrue(v.problems.isEmpty())
        assertEquals(0, v.filesChecked)
    }

    // ---- tests step

    @Test fun `the static test runner never claims to have run tests and counts test functions`() = runTest {
        val files = FakeProjectFiles(sampleProject() + (testPath to goodTest))
        val o = StaticTestRunner().run(files, listOf(testPath))
        assertFalse(o.executed)
        assertEquals(2, o.testCount)
        assertTrue(o.summary.contains("NOT run"))
        assertTrue(o.problems.isEmpty())
    }

    @Test fun `a staged test file without tests is a warning`() = runTest {
        val files = FakeProjectFiles(sampleProject() + (testPath to "package com.example.notes\n\nclass FooTest\n"))
        val o = StaticTestRunner().run(files, listOf(testPath))
        assertEquals(Severity.WARNING, o.problems.single().severity)
    }

    @Test fun `test files without a JUnit dependency are an error on the build file`() = runTest {
        val noJunit = sampleProject() + ("app/build.gradle.kts" to APP_GRADLE.replace("    testImplementation(\"junit:junit:4.13.2\")\n", "")) + (testPath to goodTest)
        val o = StaticTestRunner().run(FakeProjectFiles(noJunit), listOf(testPath))
        val p = o.problems.single()
        assertEquals("app/build.gradle.kts", p.path)
        assertEquals(Severity.ERROR, p.severity)
    }

    @Test fun `no test files means nothing to check`() = runTest {
        val o = StaticTestRunner().run(FakeProjectFiles(sampleProject()), emptyList())
        assertEquals(0, o.testFiles.size)
        assertTrue(o.problems.isEmpty())
    }

    @Test fun `AT-Test counting ignores look-alike annotations`() {
        assertEquals(2, StaticTestRunner.countTests("@Test fun a() {}\n@Test\nfun b() {}\n@TestOnly fun c() {}"))
    }

    // ---- build preparation

    private fun ready() = BuildPreparation.check(sampleProject(), ProjectType.ANDROID_KOTLIN, emptySet())

    @Test fun `a complete project is ready`() {
        val r = ready()
        assertTrue(r.items.filter { it.status == ReadinessStatus.FAIL }.toString(), r.ready)
    }

    @Test fun `missing or broken build files are failures`() {
        val noSettings = BuildPreparation.check(sampleProject() - "settings.gradle.kts", ProjectType.ANDROID_KOTLIN, emptySet())
        assertFalse(noSettings.ready)
        val noApp = BuildPreparation.check(sampleProject().plus("settings.gradle.kts" to "rootProject.name = \"x\"\n"), ProjectType.ANDROID_KOTLIN, emptySet())
        assertTrue(noApp.failures.any { it.detail.contains(":app") })
        val noNamespace = BuildPreparation.check(sampleProject() + ("app/build.gradle.kts" to "android { compileSdk = 34 }\n"), ProjectType.ANDROID_KOTLIN, emptySet())
        assertTrue(noNamespace.failures.any { it.label == "namespace set" })
        val noManifest = BuildPreparation.check(sampleProject() - "app/src/main/AndroidManifest.xml", ProjectType.ANDROID_KOTLIN, emptySet())
        assertTrue(noManifest.failures.any { it.label == "AndroidManifest.xml" })
    }

    @Test fun `androidx without the flag, compose without its plugin and room without ksp fail`() {
        val androidx = BuildPreparation.check(sampleProject() + ("gradle.properties" to "") + (FOO_PATH to "import androidx.room.Entity\nclass Foo"), ProjectType.ANDROID_KOTLIN, emptySet())
        assertTrue(androidx.failures.any { it.label == "AndroidX enabled" })
        val compose = BuildPreparation.check(sampleProject() + ("app/build.gradle.kts" to APP_GRADLE + "android { buildFeatures { compose = true } }\n"), ProjectType.ANDROID_KOTLIN, emptySet())
        assertTrue(compose.failures.any { it.label == "Compose compiler plugin" })
        val room = BuildPreparation.check(sampleProject() + ("app/build.gradle.kts" to APP_GRADLE + "dependencies { implementation(\"androidx.room:room-compiler:2.6.1\") }\n"), ProjectType.ANDROID_KOTLIN, emptySet())
        assertTrue(room.failures.any { it.label == "Room annotation processing" })
    }

    @Test fun `an intent filter without exported fails the manifest check`() {
        val manifest = LAUNCHER_MANIFEST.replace(" android:exported=\"true\"", "")
        assertTrue(BuildPreparation.check(sampleProject() + ("app/src/main/AndroidManifest.xml" to manifest), ProjectType.ANDROID_KOTLIN, emptySet()).failures.any { it.label == "Exported flags" })
    }

    @Test fun `a secret in a changed file fails build readiness`() {
        val files = sampleProject() + (FOO_PATH to "val k = \"AIzaSyA1234567890abcdefghijklmnopqrstuv\"")
        assertTrue(BuildPreparation.check(files, ProjectType.ANDROID_KOTLIN, setOf(FOO_PATH)).failures.any { it.label.contains("secrets") })
    }

    @Test fun `a web project needs an index html and nothing else`() {
        assertTrue(BuildPreparation.check(mapOf("index.html" to "<html></html>"), ProjectType.WEB, emptySet()).ready)
        assertFalse(BuildPreparation.check(mapOf("a.js" to "x"), ProjectType.WEB, emptySet()).ready)
    }

    // ---- failure analysis

    @Test fun `only errors become failures, grouped by file, and fixability follows the project files`() {
        val problems = listOf(
            AgentProblem(FOO_PATH, 3, "bad", Severity.ERROR, ProblemSource.CODE_CHECK),
            AgentProblem(FOO_PATH, 5, "worse", Severity.ERROR, ProblemSource.CODE_CHECK),
            AgentProblem(BAR_PATH, 1, "just a warning", Severity.WARNING, ProblemSource.CODE_CHECK),
            AgentProblem("gone.kt", null, "unfixable", Severity.ERROR, ProblemSource.TEST_CHECK),
        )
        val f = AgentFailureAnalyzer.analyze(problems, setOf(FOO_PATH, BAR_PATH))
        assertEquals(listOf("gone.kt", FOO_PATH), f.map { it.path })
        assertEquals(2, f.first { it.path == FOO_PATH }.problems.size)
        assertTrue(f.first { it.path == FOO_PATH }.fixable)
        assertFalse(f.first { it.path == "gone.kt" }.fixable)
    }

    // ---- report

    @Test fun `the report says nothing was written and lists files, checks, readiness and unfinished tasks`() = runTest {
        val real = FakeProjectFiles(sampleProject())
        val staged = StagedProjectFiles(real)
        staged.write(FOO_PATH, fooClass())
        staged.write(MAIN_PATH, MAIN_TEXT.replace("Notes", "Agent"))
        val set = staged.buildChangeSet("run1", "Agent: x", "s", 1L).changeSet!!
        val state = AgentRunState(
            goal = "Add notes", startedAtMillis = 0, modelCalls = 4, validation = AgentValidation(emptyList(), emptyList(), 6, emptyList()),
            plan = AgentPlan("u", emptyList(), "s", "", emptyList(), listOf(AgentTask("t1", "Make Foo", AgentTaskKind.CODE, "", status = TaskStatus.DONE, touched = listOf(FOO_PATH)), AgentTask("t2", "Make UI", AgentTaskKind.UI, "", status = TaskStatus.FAILED, note = "boom")), emptyList()),
            readiness = ready0(), recommendedDependencies = listOf(AgentDependencyAdvice("a:b:1", "because")),
            rejections = listOf(AgentRejection("x.kt", "no")),
        )
        val r = AgentReportBuilder.build(state, set, 3_000L)
        assertEquals(ReportOutcome.NEEDS_ATTENTION, r.outcome)
        assertEquals(1, r.filesCreated)
        assertEquals(1, r.filesChanged)
        assertTrue(r.markdown.contains("Nothing has been written to your project"))
        assertTrue(r.markdown.contains("NEW `$FOO_PATH`"))
        assertTrue(r.markdown.contains("CHANGED `$MAIN_PATH`"))
        assertTrue(r.markdown.contains("boom"))
        assertTrue(r.markdown.contains("a:b:1"))
        assertTrue(r.markdown.contains("x.kt"))
        assertTrue(r.markdown.contains("AI requests: 4"))
        assertEquals(ChangeKind.CREATE, set.changes.first { it.path == FOO_PATH }.kind)
    }

    @Test fun `a clean run is READY and an empty run has nothing to apply`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        staged.write(FOO_PATH, fooClass())
        val set = staged.buildChangeSet("r", "t", "s", 1L).changeSet!!
        val done = AgentRunState(goal = "g", plan = AgentPlan("u", emptyList(), "s", "", emptyList(), listOf(AgentTask("t1", "x", AgentTaskKind.CODE, "", status = TaskStatus.DONE)), emptyList()), readiness = ready0())
        assertEquals(ReportOutcome.READY, AgentReportBuilder.build(done, set, 1L).outcome)
        assertEquals(ReportOutcome.NOTHING_TO_APPLY, AgentReportBuilder.build(done, null, 1L).outcome)
    }

    private fun ready0() = BuildPreparation.check(sampleProject(), ProjectType.ANDROID_KOTLIN, emptySet())
}
