package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.ContextPolicy
import com.almus.appcreatorpro.ai.GeneratedFile
import com.almus.appcreatorpro.ai.GeneratedFileValidator
import com.almus.appcreatorpro.ai.SecretScanner
import com.almus.appcreatorpro.debug.Severity
import com.almus.appcreatorpro.debug.StaticDiagnostics
import com.almus.appcreatorpro.domain.model.ProjectType

enum class ProblemSource(val label: String) { FILE_RULES("File rules"), CODE_CHECK("Code check"), TEST_CHECK("Test check") }

data class AgentProblem(val path: String, val line: Int?, val message: String, val severity: Severity, val source: ProblemSource)

/** Step 5. [rejectedPaths] are staged files that broke a file rule and must be taken out of the result. */
data class AgentValidation(
    val problems: List<AgentProblem>,
    val warnings: List<String>,
    val filesChecked: Int,
    val rejectedPaths: List<String>,
) {
    val errors: List<AgentProblem> get() = problems.filter { it.severity == Severity.ERROR }
    val warningCount: Int get() = problems.count { it.severity == Severity.WARNING } + warnings.size
}

/**
 * Step 5: checks what the agent staged. The file rules are those of generated projects (safe paths, no secrets, no
 * duplicates, no file-and-folder clashes); then the Phase 8 static code checks run over the WHOLE merged project (so a
 * reference to an existing class or resource resolves) and only problems in files the agent touched are reported.
 * These are source-level heuristics, not a compiler: a clean result does not prove that the app builds.
 */
class AgentValidator {
    suspend fun validate(files: StagedProjectFiles): AgentValidation {
        val staged = files.stagedPaths()
        if (staged.isEmpty()) return AgentValidation(emptyList(), emptyList(), 0, emptyList())
        val problems = ArrayList<AgentProblem>()
        val warnings = ArrayList<String>()

        val report = GeneratedFileValidator.validate(staged.map { GeneratedFile(it, files.read(it)) })
        for (r in report.rejected) problems += AgentProblem(r.path, null, r.reason, Severity.ERROR, ProblemSource.FILE_RULES)
        // The validator also warns when a whole project lacks a manifest or settings file; that is only meaningful for
        // a generated project, and build readiness (step 9) checks the merged project properly.
        warnings += report.warnings.filter { w -> !w.startsWith("No AndroidManifest") && !w.startsWith("No settings.gradle") && !w.startsWith("No app/build.gradle") }

        val texts = mergedTexts(files)
        val staticIssues = try { StaticDiagnostics().analyze(texts).issues } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val stagedSet = staged.toHashSet()
        for (i in staticIssues) {
            val path = i.projectPath ?: i.file ?: continue
            if (path !in stagedSet) continue
            if (i.severity == Severity.INFO) continue
            problems += AgentProblem(path, i.line, i.message, i.severity, ProblemSource.CODE_CHECK)
            if (problems.size >= MAX_PROBLEMS) break
        }
        return AgentValidation(problems.sortedWith(compareBy<AgentProblem>({ it.severity.rank }, { it.path }, { it.line ?: 0 })), warnings, texts.size, report.rejected.map { it.path }.distinct())
    }

    companion object {
        const val MAX_PROBLEMS = 80
        const val MAX_FILES = 400
        private val checkedExtensions = setOf("kt", "java", "xml", "kts", "gradle", "properties", "toml", "html", "css", "js", "json", "md")

        /** Every text file of the merged project (agent's files included) that the checks and build preparation look at. */
        suspend fun mergedTexts(files: AiProjectFiles): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (p in files.list()) {
                if (out.size >= MAX_FILES) break
                if (ContextPolicy.isHiddenPath(p) || SecretScanner.isSensitivePath(p)) continue
                if (p.substringAfterLast('.', "").lowercase() !in checkedExtensions) continue
                try { out[p] = files.read(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* unreadable: skipped */ }
            }
            return out
        }
    }
}

/** Step 6. [executed] is false when no test could really run (the default on a phone). */
data class AgentTestOutcome(
    val executed: Boolean,
    val summary: String,
    val testFiles: List<String>,
    val testCount: Int,
    val problems: List<AgentProblem>,
)

/** A place to plug in a real test run later (for example through the cloud build). */
interface AgentTestRunner {
    suspend fun run(files: AiProjectFiles, stagedPaths: List<String>): AgentTestOutcome
}

/**
 * The only test "runner" that can exist on the phone: it does NOT execute tests. JVM unit tests need Gradle and a JDK,
 * which the app does not have, and the cloud build in this app builds an APK (assemble tasks), not the unit tests.
 * It checks that test files are well formed: each has at least one @Test, and a JUnit dependency exists.
 */
class StaticTestRunner : AgentTestRunner {
    override suspend fun run(files: AiProjectFiles, stagedPaths: List<String>): AgentTestOutcome {
        val all = files.list()
        val testFiles = all.filter { isUnitTestFile(it) }
        if (testFiles.isEmpty()) return AgentTestOutcome(false, "No unit test files in the project. Nothing to check.", emptyList(), 0, emptyList())
        val staged = stagedPaths.toHashSet()
        val problems = ArrayList<AgentProblem>()
        var total = 0
        for (p in testFiles) {
            val text = try { files.read(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { continue }
            val n = countTests(text)
            total += n
            if (n == 0 && p in staged) problems += AgentProblem(p, null, "The test file contains no @Test function.", Severity.WARNING, ProblemSource.TEST_CHECK)
        }
        val buildFiles = all.filter { it.endsWith("build.gradle.kts") || it.endsWith("build.gradle") }
        val buildTexts = buildFiles.mapNotNull { try { files.read(it) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null } }
        val hasJunit = buildTexts.any { t -> t.lineSequence().any { l -> l.contains("testImplementation") && l.contains("junit", ignoreCase = true) } }
        val stagedTests = testFiles.any { it in staged }
        if (!hasJunit && stagedTests) {
            val target = buildFiles.firstOrNull { it.startsWith("app/") } ?: buildFiles.firstOrNull() ?: "app/build.gradle.kts"
            problems += AgentProblem(target, null, "Unit test files exist but no JUnit dependency (testImplementation \"junit:junit:...\") was found in the build files.", Severity.ERROR, ProblemSource.TEST_CHECK)
        }
        val summary = "Tests were NOT run: unit tests cannot run on this device. ${testFiles.size} test file(s) with $total test function(s) were checked for structure only."
        return AgentTestOutcome(false, summary, testFiles, total, problems)
    }

    companion object {
        fun isUnitTestFile(path: String): Boolean = path.contains("/src/test/") && (path.endsWith(".kt") || path.endsWith(".java"))

        /** Counts "@Test" annotations without matching "@TestOnly" and the like. */
        fun countTests(text: String): Int {
            var count = 0
            var i = text.indexOf("@Test")
            while (i >= 0) {
                val next = text.getOrNull(i + 5)
                if (next == null || !(next.isLetterOrDigit() || next == '_')) count++
                i = text.indexOf("@Test", i + 5)
            }
            return count
        }
    }
}

enum class ReadinessStatus { OK, WARN, FAIL }

data class ReadinessItem(val label: String, val status: ReadinessStatus, val detail: String)

data class BuildReadiness(val items: List<ReadinessItem>) {
    val ready: Boolean get() = items.none { it.status == ReadinessStatus.FAIL }
    val failures: List<ReadinessItem> get() = items.filter { it.status == ReadinessStatus.FAIL }
}

/** Step 9: is the merged project ready to be sent to the cloud build? Text checks over the build files; not a build. */
object BuildPreparation {
    fun check(files: Map<String, String>, type: ProjectType?, changedPaths: Set<String>): BuildReadiness {
        val items = ArrayList<ReadinessItem>()
        fun item(label: String, status: ReadinessStatus, detail: String) { items += ReadinessItem(label, status, detail) }

        // Secrets in what the agent wrote: the guard already refuses them; this is the last look.
        val leaking = changedPaths.filter { p -> files[p]?.let { SecretScanner.containsSecret(it) } == true }
        if (leaking.isEmpty()) item("No secrets in changed files", ReadinessStatus.OK, "") else item("No secrets in changed files", ReadinessStatus.FAIL, leaking.joinToString())

        if (type == ProjectType.WEB) {
            if (files.keys.any { it == "index.html" || it.endsWith("/index.html") }) item("index.html present", ReadinessStatus.OK, "") else item("index.html present", ReadinessStatus.FAIL, "The web project has no index.html.")
            return BuildReadiness(items)
        }

        val settings = files["settings.gradle.kts"] ?: files["settings.gradle"]
        when {
            settings == null -> item("settings.gradle(.kts)", ReadinessStatus.FAIL, "Missing.")
            !(settings.contains("include(\":app\")") || settings.contains("include ':app'") || settings.contains("include(\":app\",")) -> item("settings.gradle(.kts)", ReadinessStatus.FAIL, "It does not include the :app module.")
            else -> item("settings.gradle(.kts)", ReadinessStatus.OK, "")
        }
        if (files.containsKey("build.gradle.kts") || files.containsKey("build.gradle")) item("Root build file", ReadinessStatus.OK, "") else item("Root build file", ReadinessStatus.FAIL, "build.gradle(.kts) is missing.")

        val app = files["app/build.gradle.kts"] ?: files["app/build.gradle"]
        if (app == null) {
            item("app/build.gradle(.kts)", ReadinessStatus.FAIL, "Missing.")
        } else {
            item("app/build.gradle(.kts)", ReadinessStatus.OK, "")
            if (app.contains("namespace")) item("namespace set", ReadinessStatus.OK, "") else item("namespace set", ReadinessStatus.FAIL, "Android Gradle Plugin 8 needs a namespace.")
            if (app.contains("compileSdk")) item("compileSdk set", ReadinessStatus.OK, "") else item("compileSdk set", ReadinessStatus.FAIL, "compileSdk is missing.")
            if (app.contains("applicationId")) item("applicationId set", ReadinessStatus.OK, "") else item("applicationId set", ReadinessStatus.WARN, "No applicationId; the build may fall back to the namespace.")
            if (app.contains("compose = true") || app.contains("compose true")) {
                if (app.contains("kotlin.plugin.compose")) item("Compose compiler plugin", ReadinessStatus.OK, "") else item("Compose compiler plugin", ReadinessStatus.FAIL, "Compose is on but the plugin org.jetbrains.kotlin.plugin.compose is not applied.")
            }
            if (app.contains("room-compiler") && !(app.contains("ksp") || app.contains("kapt"))) item("Room annotation processing", ReadinessStatus.FAIL, "room-compiler needs ksp(...) and the KSP plugin.")
        }

        val manifest = files["app/src/main/AndroidManifest.xml"]
        if (manifest == null) {
            item("AndroidManifest.xml", ReadinessStatus.FAIL, "app/src/main/AndroidManifest.xml is missing.")
        } else {
            item("AndroidManifest.xml", ReadinessStatus.OK, "")
            if (manifest.contains("<intent-filter") && !manifest.contains("android:exported")) item("Exported flags", ReadinessStatus.FAIL, "A component with an intent filter needs android:exported (Android 12+).")
            if (!(manifest.contains("android.intent.action.MAIN") && manifest.contains("LAUNCHER"))) item("Launcher activity", ReadinessStatus.WARN, "No launcher activity declared.")
        }

        if (files.values.any { it.contains("androidx.") }) {
            val props = files["gradle.properties"]
            if (props != null && props.contains("android.useAndroidX=true")) item("AndroidX enabled", ReadinessStatus.OK, "") else item("AndroidX enabled", ReadinessStatus.FAIL, "Code uses androidx but gradle.properties lacks android.useAndroidX=true.")
        }
        if (files.keys.any { StaticTestRunner.isUnitTestFile(it) } && app != null && !app.lines().any { it.contains("testImplementation") && it.contains("junit", ignoreCase = true) }) {
            item("JUnit dependency", ReadinessStatus.WARN, "Test files exist but app/build.gradle(.kts) has no testImplementation of JUnit.")
        }
        return BuildReadiness(items)
    }
}

data class AgentFailure(val path: String, val problems: List<AgentProblem>, val fixable: Boolean)

/** Step 7: turns the problems of steps 5 and 6 into per-file failures. Only ERRORS count; warnings go to the report. */
object AgentFailureAnalyzer {
    fun analyze(problems: List<AgentProblem>, projectPaths: Set<String>): List<AgentFailure> =
        problems.filter { it.severity == Severity.ERROR && it.path.isNotBlank() }
            .groupBy { it.path }
            .map { (path, list) -> AgentFailure(path, list, fixable = path in projectPaths) }
            .sortedBy { it.path }
}
