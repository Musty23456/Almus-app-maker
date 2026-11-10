package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiJson
import com.almus.appcreatorpro.ai.GeneratedNames
import com.almus.appcreatorpro.ai.Parsed
import com.almus.appcreatorpro.ai.RawChange
import com.almus.appcreatorpro.debug.AiPatchEdit

/** Dependency advice from a model: accepts `"group:artifact:version"` strings and `{coordinate, reason}` objects. */
object DependencyAdviceParser {
    private fun validCoordinate(c: String): Boolean {
        val parts = c.split(':')
        if (parts.size !in 2..3) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 80 && p.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == '+' } }
    }

    fun parse(value: Any?, max: Int = 20): List<AgentDependencyAdvice> =
        (value as? List<*>).orEmpty().mapNotNull { item ->
            when (item) {
                is String -> item.trim().takeIf { validCoordinate(it) }?.let { AgentDependencyAdvice(it, "") }
                is Map<*, *> -> {
                    val c = (item["coordinate"] as? String)?.trim().orEmpty()
                    if (validCoordinate(c)) AgentDependencyAdvice(c, (item["reason"] as? String).orEmpty().trim().take(200)) else null
                }
                else -> null
            }
        }.distinctBy { it.coordinate }.take(max)
}

object AgentPlanParser {
    /** Plan tasks are renumbered t1, t2, ...; "depends_on" may only point at EARLIER tasks, so a plan can never contain a cycle. */
    fun parse(text: String, truncated: Boolean, perms: AgentPermissions = AgentPermissions(), newProject: Boolean = false): Parsed<AgentPlan> {
        val obj = when (val p = AiJson.parseObject(text, truncated)) { is Parsed.Ok -> p.value; is Parsed.Bad -> return Parsed.Bad(p.reason) }
        val summary = (obj["summary"] as? String).orEmpty().trim()
        val understanding = (obj["understanding"] as? String).orEmpty().trim()
        if (summary.isEmpty() && understanding.isEmpty()) return Parsed.Bad("The plan has no summary.")
        val rawTasks = obj["tasks"] as? List<*> ?: return Parsed.Bad("The plan has no \"tasks\" list.")

        val warnings = ArrayList<String>()
        val tasks = ArrayList<AgentTask>()
        val idMap = HashMap<String, String>()
        var dropped = 0
        for (raw in rawTasks) {
            val m = raw as? Map<*, *> ?: continue
            val title = (m["title"] as? String)?.trim().orEmpty()
            if (title.isEmpty()) continue
            if (tasks.size >= perms.maxTasks) { dropped++; continue }
            val newId = "t${tasks.size + 1}"
            val oldId = (m["id"] as? String)?.trim().orEmpty()
            if (oldId.isNotEmpty()) idMap.putIfAbsent(oldId, newId)
            val kindWire = m["kind"] as? String
            val kind = AgentTaskKind.fromWire(kindWire)
            if (kind == null) warnings += "Task \"$title\" has the unknown kind \"${kindWire.orEmpty().take(30)}\"; it is treated as \"write code\"."
            val deps = AiJson.strings(m["depends_on"], 10, 30).mapNotNull { idMap[it] }.filter { it != newId }.distinct()
            val allPaths = AiJson.strings(m["paths"], 12, 200)
            val paths = allPaths.filter { AgentGuard.checkPath(it) == null }.distinct()
            if (paths.size < allPaths.size) warnings += "Task \"$title\": ${allPaths.size - paths.size} unsafe file path(s) were removed."
            tasks += AgentTask(
                id = newId, title = title.take(100), kind = kind ?: AgentTaskKind.CODE,
                detail = (m["detail"] as? String).orEmpty().trim().take(800), paths = paths, dependsOn = deps,
            )
        }
        if (dropped > 0) warnings += "The plan had too many tasks; the last $dropped were dropped (limit ${perms.maxTasks})."
        if (tasks.isEmpty()) return Parsed.Bad("The plan has no usable tasks.")

        val name = if (newProject) GeneratedNames.projectName(obj["project_name"] as? String) else null
        return Parsed.Ok(
            AgentPlan(
                understanding = understanding.take(800), assumptions = AiJson.strings(obj["assumptions"], 8, 300),
                summary = summary.ifEmpty { understanding }.take(800), architecture = (obj["architecture"] as? String).orEmpty().trim().take(800),
                dependencies = DependencyAdviceParser.parse(obj["dependencies"]), tasks = tasks, risks = AiJson.strings(obj["risks"], 10),
                projectName = name,
                packageName = if (name != null) GeneratedNames.packageName(obj["package_name"] as? String, name) else null,
                language = if (newProject) ((obj["language"] as? String).orEmpty().lowercase().takeIf { it == "java" } ?: "kotlin") else null,
                warnings = warnings,
            ),
        )
    }
}

data class ToolCall(val tool: String, val args: Map<String, String>)

data class AgentTaskResult(
    val summary: String,
    val toolCalls: List<ToolCall>,
    val changes: List<RawChange>,
    val dependencies: List<AgentDependencyAdvice>,
    val notes: List<String>,
)

/** The answer to a task or a correction request: file changes, or tool calls when the model wants to read more first. */
object AgentTaskResultParser {
    const val MAX_TOOL_CALLS = 6

    fun parse(text: String, truncated: Boolean): Parsed<AgentTaskResult> {
        val obj = when (val p = AiJson.parseObject(text, truncated)) { is Parsed.Ok -> p.value; is Parsed.Bad -> return Parsed.Bad(p.reason) }
        val calls = (obj["tool_calls"] as? List<*>).orEmpty().mapNotNull { c ->
            val m = c as? Map<*, *> ?: return@mapNotNull null
            val tool = (m["tool"] as? String)?.trim()?.lowercase() ?: return@mapNotNull null
            val args = HashMap<String, String>()
            (m["args"] as? Map<*, *>)?.forEach { (k, v) ->
                val key = k as? String
                if (key != null && (v is String || v is Number || v is Boolean)) args[key] = v.toString()
            }
            ToolCall(tool, args)
        }.take(MAX_TOOL_CALLS)
        val changes = (obj["changes"] as? List<*>).orEmpty().mapNotNull {
            val m = it as? Map<*, *> ?: return@mapNotNull null
            val path = m["path"] as? String ?: return@mapNotNull null
            val edits = (m["edits"] as? List<*>).orEmpty().mapNotNull { e ->
                val em = e as? Map<*, *> ?: return@mapNotNull null
                val find = em["find"] as? String ?: return@mapNotNull null
                val replace = em["replace"] as? String ?: return@mapNotNull null
                AiPatchEdit(find, replace)
            }
            RawChange(path, (m["action"] as? String).orEmpty().lowercase(), (m["explanation"] as? String).orEmpty().trim().take(400), m["content"] as? String, edits)
        }
        val summary = (obj["summary"] as? String).orEmpty().trim().take(800)
        if (calls.isEmpty() && changes.isEmpty() && summary.isEmpty()) return Parsed.Bad("The answer has no changes, no tool calls and no summary.")
        return Parsed.Ok(AgentTaskResult(summary, calls, changes, DependencyAdviceParser.parse(obj["dependencies"]), AiJson.strings(obj["notes"], 10)))
    }
}
