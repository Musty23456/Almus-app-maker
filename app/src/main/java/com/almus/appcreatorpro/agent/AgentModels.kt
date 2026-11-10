package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.ChangeSet

/**
 * Phase 13: vocabulary of the AI development agent. Plain Kotlin (no Android classes), so every rule below is
 * unit-tested on a normal JVM. The agent depends only on [com.almus.appcreatorpro.ai.AiBackend], never on Gemini itself.
 */

/** The kinds of work a plan can contain. [guidance] is added to the task request; [writesFiles] false = answers only. */
enum class AgentTaskKind(val wire: String, val label: String, val writesFiles: Boolean, val guidance: String) {
    ANALYZE("analyze", "Analyze", false, "Read the relevant files and report what you find in \"summary\" and \"notes\". Do not change files."),
    SCAFFOLD("scaffold", "Project structure", true, "Create the folder structure and the base files (Gradle files, manifest, resources, an empty application skeleton). Folders exist through the files inside them."),
    CODE("code", "Write code", true, "Write the requested source code as complete files. Keep package lines in line with the folder."),
    EDIT("edit", "Edit files", true, "Change the existing files that are shown. Use targeted \"edits\" for small changes. Keep everything that was not asked to change."),
    DEPENDENCIES("dependencies", "Dependencies", true, "Recommend only libraries that are needed. Add them to the module build file with exact versions and list them in \"dependencies\" with a reason."),
    API("api", "API integration", true, "Write the network layer for the described API (client, models, repository). Add the INTERNET permission to the manifest if it is missing. Never put keys or tokens in code: read them from BuildConfig or user input."),
    DATABASE("database", "Database", true, "Design the schema (entities, relations, indexes) and write the database code (for Room: entities, DAOs, database class). Add the needed dependencies and plugins."),
    UI("ui", "User interface", true, "Build the screens for the requested design (Jetpack Compose or XML layouts, matching what the project already uses) and every string, color and style they reference."),
    EXPLAIN("explain", "Explain", false, "Explain the given code for a beginner in \"summary\" and \"notes\". Do not change files."),
    REFACTOR("refactor", "Refactor", true, "Improve the structure of the shown code without changing behaviour. Keep public names unless the task says otherwise."),
    DOCS("docs", "Documentation", true, "Write the requested documentation as Markdown (README.md or files under docs/). Describe only what exists in the project."),
    TESTS("tests", "Tests", true, "Write JUnit 4 unit tests under app/src/test/java/<package path>/ for the code that exists. Add the JUnit test dependency if it is missing."),
    FIX("fix", "Fix", true, "Fix the described problem with the smallest change that solves it."),
    ;

    companion object {
        fun fromWire(s: String?): AgentTaskKind? = entries.firstOrNull { it.wire == s?.trim()?.lowercase() }
    }
}

enum class TaskStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/** One step of the plan. [note] is the result (when DONE) or the reason (when FAILED or SKIPPED); [touched] the files it changed. */
data class AgentTask(
    val id: String,
    val title: String,
    val kind: AgentTaskKind,
    val detail: String,
    val paths: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val status: TaskStatus = TaskStatus.PENDING,
    val attempts: Int = 0,
    val note: String = "",
    val touched: List<String> = emptyList(),
)

data class AgentDependencyAdvice(val coordinate: String, val reason: String)

data class AgentPlan(
    val understanding: String,
    val assumptions: List<String>,
    val summary: String,
    val architecture: String,
    val dependencies: List<AgentDependencyAdvice>,
    val tasks: List<AgentTask>,
    val risks: List<String>,
    /** Only set when the plan is for a NEW project. */
    val projectName: String? = null,
    val packageName: String? = null,
    val language: String? = null,
    val warnings: List<String> = emptyList(),
) {
    fun asText(): String = buildString {
        append("Summary: ").append(summary).append('\n')
        if (architecture.isNotBlank()) append("Architecture: ").append(architecture).append('\n')
        tasks.forEach { append("- ").append(it.id).append(" [").append(it.kind.wire).append("] ").append(it.title).append('\n') }
    }
}

/** The ten steps of the workflow, in order. Steps 1 to 3 are produced by one planning request. */
enum class AgentStage(val step: Int, val label: String) {
    UNDERSTAND(1, "Understand the requirements"),
    PLAN(2, "Generate a development plan"),
    BREAKDOWN(3, "Break the plan into tasks"),
    EXECUTE(4, "Generate or modify files"),
    VALIDATE(5, "Validate the files"),
    TEST(6, "Run the available tests"),
    ANALYZE_FAILURES(7, "Analyze failures"),
    CORRECT(8, "Propose or apply safe corrections"),
    PREPARE_BUILD(9, "Prepare for building"),
    REPORT(10, "Completion report"),
}

enum class AgentPhase { IDLE, PLANNING, AWAITING_APPROVAL, RUNNING, AWAITING_REVIEW, APPLIED, FAILED, CANCELLED }

enum class AgentLogLevel { STEP, INFO, WARN, ERROR }

data class AgentLogEntry(val atMillis: Long, val level: AgentLogLevel, val text: String, val taskId: String? = null)

enum class AgentSpeaker { USER, AGENT }

/** The conversation of one run: the goal, clarifications and answers. Sent with every request, so the agent keeps context. */
data class AgentMessage(val speaker: AgentSpeaker, val text: String, val atMillis: Long)

data class AgentOptions(
    /** true: problems found by the checks are corrected automatically (in the staging area only). false: the user decides. */
    val autoCorrect: Boolean = true,
)

/** A change the agent was not allowed to make, and why. Shown to the user; never silently dropped. */
data class AgentRejection(val path: String, val reason: String, val taskId: String? = null)

data class AgentCorrection(val round: Int, val files: List<String>, val summary: String, val errorsBefore: Int, val errorsAfter: Int, val undone: Boolean)

data class AgentRunState(
    val runId: String? = null,
    val goal: String = "",
    val phase: AgentPhase = AgentPhase.IDLE,
    val stage: AgentStage? = null,
    val plan: AgentPlan? = null,
    /** The user approved the plan. Nothing is executed before that. */
    val planApproved: Boolean = false,
    val currentTaskId: String? = null,
    /** Streaming: characters received so far for the current request, and the last part of the text (secrets removed). */
    val streamedChars: Int = 0,
    val liveTail: String = "",
    val logs: List<AgentLogEntry> = emptyList(),
    val messages: List<AgentMessage> = emptyList(),
    /** What the agent would change, compared with the real files. Nothing of it is in the project until the user applies it. */
    val changeSet: ChangeSet? = null,
    val validation: AgentValidation? = null,
    val testOutcome: AgentTestOutcome? = null,
    val readiness: BuildReadiness? = null,
    val report: AgentReport? = null,
    val corrections: List<AgentCorrection> = emptyList(),
    val recommendedDependencies: List<AgentDependencyAdvice> = emptyList(),
    val rejections: List<AgentRejection> = emptyList(),
    val options: AgentOptions = AgentOptions(),
    val modelCalls: Int = 0,
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long? = null,
    val error: String? = null,
    val retryable: Boolean = false,
    val appliedRecordId: String? = null,
) {
    val tasks: List<AgentTask> get() = plan?.tasks.orEmpty()
    val busy: Boolean get() = phase == AgentPhase.PLANNING || phase == AgentPhase.RUNNING

    /** 0..1 over the tasks (finished or skipped count as done). */
    val taskProgress: Float
        get() = if (tasks.isEmpty()) 0f else tasks.count { it.status == TaskStatus.DONE || it.status == TaskStatus.SKIPPED }.toFloat() / tasks.size

    /** True when the agent holds results that are not in the project yet (so discarding them loses work). */
    val hasUnappliedWork: Boolean get() = phase != AgentPhase.APPLIED && (changeSet != null || tasks.any { it.status == TaskStatus.DONE && it.touched.isNotEmpty() })
}
