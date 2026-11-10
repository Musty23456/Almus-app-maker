package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.ContextBundle

/**
 * The text the agent sends. Project files, logs and the user's own notes are data: the backend instructions tell the
 * model not to obey instructions found inside them. Everything here is built from values the app already checked.
 */
object AgentPrompts {
    const val MAX_NOTES = 8
    const val MAX_MEMORY_ENTRIES = 12

    private fun notes(messages: List<AgentMessage>): String {
        val user = messages.filter { it.speaker == AgentSpeaker.USER }
        if (user.size <= 1) return ""
        return "USER NOTES (oldest first; the first line is the goal):\n" + user.takeLast(MAX_NOTES).joinToString("\n") { "- " + it.text.take(500) } + "\n\n"
    }

    fun planRequest(goal: String, bundle: ContextBundle, messages: List<AgentMessage>, previous: AgentPlan?, newProject: Boolean): String = buildString {
        append("GOAL:\n").append(goal.trim()).append("\n\n")
        append(notes(messages))
        if (previous != null) {
            append("PREVIOUS PLAN (the user wants it changed; their newest note above says how):\n").append(previous.asText()).append('\n')
        }
        append(if (newProject) "PROJECT STATE: a NEW, empty project. Include project_name, package_name and language.\n\n" else "PROJECT STATE: an existing project. Do not include project_name or package_name.\n\n")
        append(bundle.render(includeStructure = true))
    }

    /** What finished tasks did, newest last. This is the agent's task memory. */
    fun memory(tasks: List<AgentTask>): String {
        val done = tasks.filter { it.status == TaskStatus.DONE }.takeLast(MAX_MEMORY_ENTRIES)
        if (done.isEmpty()) return ""
        return "FINISHED TASKS (their files are in the project below, marked as staged by the agent):\n" + done.joinToString("\n") { t ->
            "- ${t.id} [${t.kind.wire}] ${t.title}: ${t.note.take(200)}" + if (t.touched.isNotEmpty()) " (files: ${t.touched.take(8).joinToString()})" else ""
        } + "\n\n"
    }

    fun taskRequest(goal: String, plan: AgentPlan, task: AgentTask, messages: List<AgentMessage>, previousProblem: String?, bundle: ContextBundle): String = buildString {
        append("OVERALL GOAL:\n").append(goal.trim()).append("\n\n")
        append("PLAN SUMMARY: ").append(plan.summary).append('\n')
        if (plan.architecture.isNotBlank()) append("ARCHITECTURE: ").append(plan.architecture).append('\n')
        append('\n')
        append(memory(plan.tasks))
        append("YOUR TASK ${task.id} (kind: ${task.kind.wire}): ${task.title}\n")
        if (task.detail.isNotBlank()) append(task.detail).append('\n')
        if (task.paths.isNotEmpty()) append("Expected files: ").append(task.paths.joinToString()).append('\n')
        append("Guidance: ").append(task.kind.guidance).append("\n\n")
        append(notes(messages))
        if (!previousProblem.isNullOrBlank()) append("YOUR PREVIOUS ATTEMPT AT THIS TASK FAILED: ").append(previousProblem.take(600)).append("\nAvoid that problem this time.\n\n")
        append(bundle.render(includeStructure = true))
    }

    fun fixRequest(failures: List<AgentFailure>, bundle: ContextBundle): String = buildString {
        append("PROBLEMS FOUND BY AUTOMATIC CHECKS (in files created or changed by the agent):\n")
        for (f in failures) {
            append(f.path).append(":\n")
            f.problems.take(12).forEach { p -> append("- ").append(p.line?.let { "line $it: " } ?: "").append(p.message.take(300)).append('\n') }
        }
        append("\nFix exactly these problems.\n\n")
        append(bundle.render(includeStructure = false))
    }

    fun retryHint(reason: String, truncated: Boolean): String =
        if (truncated) "Your answer was cut off because it was too long. Answer again with ONE JSON object, with fewer and smaller files (at most 5 files, use \"edits\" where you can)."
        else "Your answer could not be used: ${reason.take(200)} Answer again with ONE valid JSON object and nothing else."

    fun toolResults(outputs: List<ToolOutput>): String =
        "TOOL RESULTS:\n" + outputs.joinToString("\n\n") { "[${it.tool}]\n${it.text}" } + "\n\nNow answer with the JSON object for the task (\"changes\"), not with more tool calls unless you really need them."
}
