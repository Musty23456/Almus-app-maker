package com.almus.appcreatorpro.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 22: classification, root cause vs secondary, severity, files, confidence and honesty of the diagnosis layer. */
class DiagnosisEngineTest {
    private val projectFiles = listOf(
        "app/build.gradle.kts", "settings.gradle.kts", "build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/java/com/example/app/MainActivity.kt",
        "app/src/main/res/values/strings.xml", "app/src/main/res/layout/activity_main.xml",
        ".github/workflows/build.yml",
    )
    private fun engine() = DiagnosisEngine(ProjectPathResolver(projectFiles), projectFiles)
    private fun diagnose(log: String, truncated: Boolean = false) = engine().run(log, truncated).diagnosis

    private val kotlinLog = """
        > Task :app:compileDebugKotlin FAILED
        e: file:///home/runner/work/app/app/app/src/main/java/com/example/app/MainActivity.kt:12:9 Unresolved reference: foo
        FAILURE: Build failed with an exception.
        * What went wrong:
        Execution failed for task ':app:compileDebugKotlin'.
        > Compilation error. See log for more details
        BUILD FAILED in 12s
    """.trimIndent()

    // ---- classification ----------------------------------------------------------------------------------------------

    @Test fun kotlinErrorIsClassifiedAndLocated() {
        val d = diagnose(kotlinLog)
        val p = d.primary!!
        assertEquals(DiagnosisCategory.KOTLIN_COMPILATION, p.category)
        assertEquals(ErrorRole.ROOT_CAUSE, p.role)
        assertEquals(SeverityLevel.CRITICAL, p.severity)
        val f = p.affectedFiles.first()
        assertEquals("app/src/main/java/com/example/app/MainActivity.kt", f.path)
        assertEquals(12, f.line)
        assertEquals(LocationBasis.LOG_LOCATION, f.basis)
        assertTrue(f.inProject)
        assertNotNull(p.why)
        assertTrue(p.why!!.contains("foo"))
    }

    @Test fun javaErrorIsClassified() {
        val d = diagnose("app/src/main/java/com/example/app/Util.java:5: error: cannot find symbol\n  symbol:   variable x\nBUILD FAILED")
        assertEquals(DiagnosisCategory.JAVA_COMPILATION, d.primary!!.category)
    }

    @Test fun manifestAndXmlAndResourceAreSeparated() {
        val log = """
            app/src/main/res/layout/activity_main.xml:8: AAPT: error: attribute android:foo not found.
            error: resource string/missing (aka com.example.app:string/missing) not found.
            BUILD FAILED
        """.trimIndent()
        val cats = diagnose(log).issues.map { it.category }.toSet()
        assertTrue(cats.any { it == DiagnosisCategory.RESOURCE_LINKING })
    }

    @Test fun xmlParseErrorIsXml() {
        val d = diagnose("app/src/main/res/values/strings.xml:3: AAPT: error: unexpected end tag </strng>\nBUILD FAILED")
        assertTrue(d.issues.any { it.category == DiagnosisCategory.XML || it.category == DiagnosisCategory.RESOURCE_LINKING })
    }

    @Test fun sdkErrorIsClassifiedAsEnvironment() {
        val d = diagnose("SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable.\nBUILD FAILED")
        val p = d.primary!!
        assertEquals(DiagnosisCategory.ANDROID_SDK, p.category)
        assertEquals(FixScope.ENVIRONMENT, p.scope)
    }

    // ---- GitHub Actions ----------------------------------------------------------------------------------------------

    @Test fun actionNotFound() {
        val d = diagnose("##[error]Unable to resolve action `actions/checkout@v99`, unable to find version `v99`")
        val p = d.primary!!
        assertEquals(DiagnosisCategory.GITHUB_ACTIONS, p.category)
        assertEquals("gha.action-not-found", p.issue.rule)
        assertEquals(".github/workflows/build.yml", p.affectedFiles.first().path)
        assertEquals(LocationBasis.INFERRED, p.affectedFiles.first().basis)
    }

    @Test fun workflowSyntaxErrorHasFileAndLineFromLog() {
        val d = diagnose("The workflow is not valid. .github/workflows/build.yml (Line: 14, Col: 9): Unexpected value 'runs-ons'")
        val p = d.primary!!
        assertEquals("gha.workflow-syntax", p.issue.rule)
        val f = p.affectedFiles.first()
        assertEquals(14, f.line)
        assertEquals(LocationBasis.LOG_LOCATION, f.basis)
        assertTrue(p.issue.message.contains("Unexpected value"))
    }

    @Test fun permissionDiskFullAndExitCode137() {
        assertEquals("gha.permission", diagnose("##[error]Resource not accessible by integration").primary!!.issue.rule)
        assertEquals("gha.disk-full", diagnose("##[error]No space left on device").primary!!.issue.rule)
        val killed = diagnose("##[error]Process completed with exit code 137.").primary!!
        assertEquals("gha.exit-code", killed.issue.rule)
        assertTrue("must not claim OOM as fact", killed.why!!.contains("does not prove"))
        assertTrue(killed.confidence.level != Confidence.HIGH)
    }

    @Test fun plainExitCode1IsNotAnError() {
        val d = diagnose("##[error]Process completed with exit code 1.")
        assertTrue(d.rootCauses.none { it.confidence.sufficient })
    }

    @Test fun unexplainedRunnerErrorIsKeptButLowConfidence() {
        val d = diagnose("##[error]Something odd happened in the runner")
        val p = d.primary!!
        assertEquals("gha.error-annotation", p.issue.rule)
        assertEquals(Confidence.LOW, p.confidence.level)
        assertFalse(p.confidence.sufficient)
        assertTrue(d.limitations.any { it.contains("enough information") })
    }

    @Test fun markerOverridesDefaultSeverity() {
        val d = diagnose("##[warning]Resource not accessible by integration")
        assertTrue(d.issues.first().issue.severity == Severity.WARNING)
        assertEquals(ErrorRole.INFORMATIONAL, d.issues.first().role)
    }

    // ---- network and dependency ----------------------------------------------------------------------------------------

    private val networkLog = """
        > Task :app:checkDebugAarMetadata FAILED
        FAILURE: Build failed with an exception.
        * What went wrong:
        Execution failed for task ':app:checkDebugAarMetadata'.
        > Could not resolve all files for configuration ':app:debugRuntimeClasspath'.
           > Could not resolve com.squareup.retrofit2:retrofit:2.9.0.
              > Could not GET 'https://repo.maven.apache.org/maven2/com/squareup/retrofit2/retrofit/2.9.0/retrofit-2.9.0.pom'. Received status code 503 from server: Service Unavailable
        BUILD FAILED in 3s
    """.trimIndent()

    @Test fun downloadFailureIsRootAndDependencyIsSecondary() {
        val d = diagnose(networkLog)
        val root = d.primary!!
        assertEquals(DiagnosisCategory.NETWORK, root.category)
        assertEquals(FixScope.ENVIRONMENT, root.scope)
        val dep = d.issues.first { it.issue.rule == "dependency.not-found" }
        assertEquals(ErrorRole.SECONDARY, dep.role)
        assertEquals(root.id, dep.link!!.rootIssueId)
        assertEquals(Confidence.HIGH, dep.link!!.confidence)
        assertEquals(SeverityLevel.HIGH, dep.severity)
        assertEquals(listOf(dep.id), root.secondaryIds)
    }

    @Test fun http503IsTransientAndNotRatedHighConfidence() {
        val root = diagnose(networkLog).primary!!
        assertTrue(root.why!!.contains("temporar"))
        assertTrue(root.confidence.score <= 70)
        assertTrue(root.confidence.reasons.any { it.contains("temporary") })
    }

    @Test fun http404IsADependencyProblemNotANetworkOne() {
        val log = """
            > Could not resolve com.example:missing:9.9.9.
               > Could not GET 'https://repo.maven.apache.org/maven2/com/example/missing/9.9.9/missing-9.9.9.pom'. Received status code 404 from server: Not Found
            BUILD FAILED
        """.trimIndent()
        val d = diagnose(log)
        val primary = d.primary!!
        assertEquals("dependency.not-found", primary.issue.rule)
        assertEquals(DiagnosisCategory.DEPENDENCY_RESOLUTION, primary.category)
        val http = d.issues.first { it.issue.rule == "dependency.network" }
        assertEquals(ErrorRole.SECONDARY, http.role)
        assertEquals(primary.id, http.link!!.rootIssueId)
    }

    @Test fun networkKindsAreDistinguished() {
        fun kind(s: String) = NetworkInterpreter.interpret(s)!!.kind
        assertEquals(NetworkKind.HTTP_FORBIDDEN, kind("Could not GET 'https://x.example/a.pom'. Received status code 403 from server"))
        assertEquals(NetworkKind.HTTP_RATE_LIMITED, kind("Received status code 429 from server"))
        assertEquals(NetworkKind.UNKNOWN_HOST, kind("java.net.UnknownHostException: repo.maven.apache.org"))
        assertEquals(NetworkKind.TIMEOUT, kind("Read timed out"))
        assertEquals(NetworkKind.TLS, kind("PKIX path building failed: unable to find valid certification path"))
        assertNull(NetworkInterpreter.interpret("something unrelated"))
    }

    @Test fun tlsFailureIsDetectedByTheSignalLayer() {
        val d = diagnose("javax.net.ssl.SSLHandshakeException: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target\nBUILD FAILED")
        assertTrue(d.issues.any { it.category == DiagnosisCategory.NETWORK })
    }

    @Test fun duplicateClassIsAConflict() {
        val d = diagnose("Duplicate class kotlin.collections.jdk8.CollectionsJDK8Kt found in modules kotlin-stdlib-1.8.0 (org.jetbrains.kotlin:kotlin-stdlib:1.8.0) and kotlin-stdlib-jdk8-1.7.0\nBUILD FAILED")
        val p = d.primary!!
        assertEquals(DiagnosisCategory.DEPENDENCY_CONFLICT, p.category)
        assertEquals("app/build.gradle.kts", p.affectedFiles.first().path)
        assertEquals(LocationBasis.INFERRED, p.affectedFiles.first().basis)
    }

    @Test fun versionConstraintAndPackagingConflicts() {
        val a = diagnose("Cannot find a version of 'androidx.core:core' that satisfies the version constraints:\nBUILD FAILED").primary!!
        assertEquals(DiagnosisCategory.DEPENDENCY_CONFLICT, a.category)
        val b = diagnose("More than one file was found with OS independent path 'META-INF/LICENSE.md'\nBUILD FAILED").primary!!
        assertEquals(DiagnosisCategory.DEPENDENCY_CONFLICT, b.category)
        assertTrue(b.solution!!.contains("META-INF/LICENSE.md"))
        val c = diagnose("The Android Gradle plugin supports only Kotlin Gradle plugin version 1.8.10 and higher.\nBUILD FAILED").primary!!
        assertTrue(c.solution!!.contains("1.8.10"))
    }

    // ---- root cause vs secondary ------------------------------------------------------------------------------------

    @Test fun unresolvedRIsSecondaryOfResourceError() {
        val log = """
            app/src/main/res/layout/activity_main.xml:8: AAPT: error: attribute android:foo not found.
            e: file:///w/app/src/main/java/com/example/app/MainActivity.kt:9:20 Unresolved reference: R
            BUILD FAILED
        """.trimIndent()
        val d = diagnose(log)
        val r = d.issues.first { it.issue.symbol == "R" }
        assertEquals(ErrorRole.SECONDARY, r.role)
        assertEquals(Confidence.MEDIUM, r.link!!.confidence)
        assertEquals(DiagnosisCategory.RESOURCE_LINKING, d.primary!!.category)
    }

    @Test fun unrelatedUnresolvedReferenceStaysARootCause() {
        val log = """
            app/src/main/res/layout/activity_main.xml:8: AAPT: error: attribute android:foo not found.
            e: file:///w/app/src/main/java/com/example/app/MainActivity.kt:9:20 Unresolved reference: helper
            BUILD FAILED
        """.trimIndent()
        val d = diagnose(log)
        assertEquals(ErrorRole.ROOT_CAUSE, d.issues.first { it.issue.symbol == "helper" }.role)
    }

    @Test fun errorsAfterASyntaxErrorInTheSameFileAreSecondary() {
        val log = """
            e: file:///w/app/src/main/java/com/example/app/MainActivity.kt:10:5 Expecting a top level declaration
            e: file:///w/app/src/main/java/com/example/app/MainActivity.kt:20:5 Unresolved reference: zzz
            BUILD FAILED
        """.trimIndent()
        val d = diagnose(log)
        val later = d.issues.first { it.issue.line == 20 }
        assertEquals(ErrorRole.SECONDARY, later.role)
        assertEquals(10, d.primary!!.issue.line)
    }

    @Test fun errorBeforeTheSyntaxErrorIsNotAttributedToIt() {
        val log = """
            e: file:///w/app/src/main/java/com/example/app/MainActivity.kt:3:5 Unresolved reference: early
            e: file:///w/app/src/main/java/com/example/app/MainActivity.kt:10:5 Expecting a top level declaration
            BUILD FAILED
        """.trimIndent()
        assertEquals(2, diagnose(log).rootCauses.size)
    }

    @Test fun missingLibraryExplainsMatchingUnresolvedNameOnly() {
        val log = """
            > Could not resolve com.squareup.retrofit2:retrofit:2.9.0.
            e: file:///w/app/src/main/java/com/example/app/MainActivity.kt:4:8 Unresolved reference: retrofit2
            e: file:///w/app/src/main/java/com/example/app/MainActivity.kt:30:8 Unresolved reference: somethingElse
            BUILD FAILED
        """.trimIndent()
        val d = diagnose(log)
        assertEquals(ErrorRole.SECONDARY, d.issues.first { it.issue.symbol == "retrofit2" }.role)
        assertEquals(ErrorRole.ROOT_CAUSE, d.issues.first { it.issue.symbol == "somethingElse" }.role)
        assertTrue("unlinked names must be reported as uncertain", d.limitations.any { it.contains("could not be tied") })
    }

    @Test fun repeatedUnresolvedReferencesAreMergedWithAllLocations() {
        val log = (1..3).joinToString("\n") { "e: file:///w/app/src/main/java/com/example/app/MainActivity.kt:${it * 10}:5 Unresolved reference: foo" } + "\nBUILD FAILED"
        val d = diagnose(log)
        val foo = d.issues.filter { it.issue.symbol == "foo" }
        assertEquals(1, foo.size)
        assertEquals(3, foo.first().occurrences)
        assertEquals(2, foo.first().mergedIssueIds.size)
        assertEquals(3, foo.first().affectedFiles.size)
        assertNotNull(d.find(foo.first().mergedIssueIds.first()))
    }

    @Test fun twoIndependentProblemsAreBothRootCauses() {
        val log = """
            e: file:///w/app/src/main/java/com/example/app/MainActivity.kt:3:5 Unresolved reference: a1
            app/src/main/res/layout/activity_main.xml:8: AAPT: error: attribute android:foo not found.
            BUILD FAILED
        """.trimIndent()
        val d = diagnose(log)
        assertTrue(d.rootCauses.size >= 2)
        assertTrue(d.limitations.any { it.contains("separate root causes") })
    }

    // ---- severity -----------------------------------------------------------------------------------------------------

    @Test fun warningsAreLowOrMediumAndNeverRoots() {
        val log = "w: file:///w/app/src/main/java/com/example/app/MainActivity.kt:3:5 Variable 'x' is never used\nDeprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.\nBUILD SUCCESSFUL in 4s"
        val d = diagnose(log)
        assertTrue(d.rootCauses.isEmpty())
        assertNull(d.primary)
        assertTrue(d.issues.any { it.severity == SeverityLevel.MEDIUM })
        assertTrue(d.issues.all { it.role == ErrorRole.INFORMATIONAL })
    }

    // ---- stack traces ---------------------------------------------------------------------------------------------------

    private val traceLog = """
        FAILURE: Build failed with an exception.
        * What went wrong:
        Execution failed for task ':app:compileDebugKotlin'.
        > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers${'$'}GradleKotlinCompilerWorkAction
        * Exception is:
        org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':app:compileDebugKotlin'.
        	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.execute(ExecuteActionsTaskExecuter.java:130)
        Caused by: java.lang.NullPointerException: Cannot invoke "String.length()" because "s" is null
        	at com.example.app.MainActivity.onCreate(MainActivity.kt:42)
        	at android.app.Activity.performCreate(Activity.java:8000)
        	... 12 more
        BUILD FAILED in 2s
    """.trimIndent()

    @Test fun stackTraceChainRootCauseAndProjectFrame() {
        val lines = LogCleaner.lines(traceLog)
        val t = StackTraceAnalyzer(ProjectPathResolver(projectFiles)).analyze(lines).single()
        assertEquals(2, t.chain.size)
        assertEquals("NullPointerException", t.rootCause.simpleName)
        assertEquals("TaskExecutionException", t.outermost.simpleName)
        val f = t.firstProjectFrame!!
        assertEquals("app/src/main/java/com/example/app/MainActivity.kt", f.projectPath)
        assertEquals(42, f.line)
        assertFalse(t.frameworkOnly)
        assertEquals(12, t.rootCause.omittedFrames)
    }

    @Test fun stackTraceContributesAffectedFileAndExplanation() {
        val d = diagnose(traceLog)
        val withTrace = d.issues.first { it.stackTrace != null }
        assertTrue(withTrace.affectedFiles.any { it.basis == LocationBasis.STACK_TRACE && it.line == 42 })
    }

    @Test fun frameworkOnlyTraceDoesNotInventAProjectLine() {
        val log = """
            Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
            	at org.gradle.internal.Foo.bar(Foo.java:10)
            	at java.base/java.lang.Thread.run(Thread.java:833)
        """.trimIndent()
        val d = diagnose(log)
        val p = d.primary!!
        assertEquals(DiagnosisCategory.GRADLE, p.category)
        assertTrue(p.affectedFiles.none { it.inProject && it.basis != LocationBasis.INFERRED })
        assertTrue(p.solution!!.contains("jvmargs"))
        assertTrue(d.limitations.any { it.contains("no frame from your project") })
    }

    @Test fun nonTraceTextIsNotMistakenForATrace() {
        val lines = LogCleaner.lines("NullPointerException is common\nCaused by something\nBUILD FAILED")
        assertTrue(StackTraceAnalyzer().analyze(lines).isEmpty())
    }

    @Test fun logcatPrefixesAreStripped() {
        val log = "E/AndroidRuntime( 123): java.lang.IllegalStateException: boom\nE/AndroidRuntime( 123): \tat com.example.app.MainActivity.onCreate(MainActivity.kt:7)"
        val t = StackTraceAnalyzer(ProjectPathResolver(projectFiles)).analyze(LogCleaner.lines(log)).single()
        assertEquals("IllegalStateException", t.rootCause.simpleName)
        assertEquals(7, t.firstProjectFrame!!.line)
    }

    @Test fun noSuchMethodErrorIsADependencyConflict() {
        val log = "java.lang.NoSuchMethodError: 'void okio.Buffer.foo()'\n\tat com.example.app.MainActivity.x(MainActivity.kt:3)\nBUILD FAILED"
        val p = diagnose(log).primary!!
        assertEquals(DiagnosisCategory.DEPENDENCY_CONFLICT, p.category)
    }

    // ---- honesty --------------------------------------------------------------------------------------------------------

    @Test fun unrecognisedFailureHasInsufficientEvidence() {
        val d = diagnose("Something went sideways\nBUILD FAILED in 1s")
        val p = d.primary!!
        assertFalse(p.confidence.sufficient)
        assertEquals(Confidence.LOW, p.confidence.level)
        assertTrue(d.limitations.any { it.contains("enough information") })
    }

    @Test fun ambiguousMessagesAreNeverRatedAsCertain() {
        val p = diagnose(kotlinLog).primary!!
        assertTrue("score ${p.confidence.score}", p.confidence.score <= 70)
        assertTrue(p.confidence.reasons.any { it.contains("different causes") })
    }

    @Test fun truncatedLogLowersConfidenceAndIsDisclosed() {
        val full = diagnose(kotlinLog).primary!!
        val cut = diagnose(kotlinLog, truncated = true)
        assertTrue(cut.primary!!.confidence.score < full.confidence.score)
        assertTrue(cut.limitations.any { it.contains("end of a long log") })
    }

    @Test fun fileOutsideProjectIsDisclosed() {
        val d = diagnose("e: file:///w/other/Unknown.kt:3:5 Unresolved reference: q\nBUILD FAILED")
        val p = d.primary!!
        assertFalse(p.affectedFiles.first().inProject)
        assertNotNull(p.affectedFiles.first().note)
        assertTrue(d.limitations.any { it.contains("could not be matched") })
    }

    @Test fun secretsInLogsAreRedactedInIssues() {
        val d = diagnose("##[error]Failed with token ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD")
        assertTrue(d.issues.none { it.issue.message.contains("ghp_abcdefghijklmnopqrstuvwxyz") })
    }

    @Test fun emptyLogGivesEmptyDiagnosis() {
        val d = diagnose("")
        assertTrue(d.isEmpty)
        assertNull(d.primary)
    }

    @Test fun hugeLogStaysFast() {
        val sb = StringBuilder()
        repeat(40_000) { sb.append("> Task :app:something$it\n") }
        sb.append(kotlinLog)
        val t0 = System.nanoTime()
        val d = diagnose(sb.toString())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNotNull(d.primary)
        assertTrue("took $ms ms", ms < 8000)
    }
}
