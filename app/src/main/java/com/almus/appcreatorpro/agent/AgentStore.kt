package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.arr
import com.almus.appcreatorpro.service.github.asObj
import com.almus.appcreatorpro.service.github.bool
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.obj
import com.almus.appcreatorpro.service.github.str

/** Everything of a run that must survive leaving the screen or closing the app (task memory, progress, staged files, log). */
data class AgentSnapshot(
    val runId: String,
    val goal: String,
    val phase: AgentPhase,
    val stage: AgentStage?,
    val plan: AgentPlan?,
    val planApproved: Boolean,
    val messages: List<AgentMessage>,
    val logs: List<AgentLogEntry>,
    val options: AgentOptions,
    val staged: Map<String, String>,
    val baseHashes: Map<String, String?>,
    val recommendedDependencies: List<AgentDependencyAdvice>,
    val rejections: List<AgentRejection>,
    val corrections: List<AgentCorrection>,
    val reportMarkdown: String?,
    val modelCalls: Int,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val error: String?,
    val appliedRecordId: String?,
)

interface AgentRunStore {
    suspend fun load(projectId: String): AgentSnapshot?
    suspend fun save(projectId: String, snapshot: AgentSnapshot)
    suspend fun clear(projectId: String)
}

class InMemoryAgentRunStore : AgentRunStore {
    private val map = HashMap<String, AgentSnapshot>()
    override suspend fun load(projectId: String): AgentSnapshot? = map[projectId]
    override suspend fun save(projectId: String, snapshot: AgentSnapshot) { map[projectId] = snapshot }
    override suspend fun clear(projectId: String) { map.remove(projectId) }
}

object AgentSnapshotJson {
    private const val VERSION = 1

    private inline fun <reified E : Enum<E>> enumOf(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

    private fun taskToMap(t: AgentTask): Map<String, Any?> = mapOf(
        "id" to t.id, "title" to t.title, "kind" to t.kind.wire, "detail" to t.detail, "paths" to t.paths, "dependsOn" to t.dependsOn,
        "status" to t.status.name, "attempts" to t.attempts, "note" to t.note, "touched" to t.touched,
    )

    private fun strings(v: Any?): List<String> = (v as? List<*>).orEmpty().mapNotNull { it as? String }

    private fun taskFromMap(o: Map<String, Any?>): AgentTask? = AgentTask(
        id = o.str("id") ?: return null, title = o.str("title") ?: return null,
        kind = AgentTaskKind.fromWire(o.str("kind")) ?: AgentTaskKind.CODE, detail = o.str("detail").orEmpty(),
        paths = strings(o["paths"]), dependsOn = strings(o["dependsOn"]),
        status = enumOf<TaskStatus>(o.str("status")) ?: TaskStatus.PENDING,
        attempts = (o["attempts"] as? Number)?.toInt() ?: 0, note = o.str("note").orEmpty(), touched = strings(o["touched"]),
    )

    private fun adviceToMap(a: AgentDependencyAdvice) = mapOf("coordinate" to a.coordinate, "reason" to a.reason)
    private fun adviceFromMap(o: Map<String, Any?>): AgentDependencyAdvice? = AgentDependencyAdvice(o.str("coordinate") ?: return null, o.str("reason").orEmpty())

    fun toJson(s: AgentSnapshot): String = MiniJson.stringify(
        mapOf(
            "v" to VERSION, "runId" to s.runId, "goal" to s.goal, "phase" to s.phase.name, "stage" to s.stage?.name, "approved" to s.planApproved,
            "plan" to s.plan?.let { p ->
                mapOf(
                    "understanding" to p.understanding, "assumptions" to p.assumptions, "summary" to p.summary, "architecture" to p.architecture,
                    "dependencies" to p.dependencies.map { adviceToMap(it) }, "tasks" to p.tasks.map { taskToMap(it) }, "risks" to p.risks,
                    "projectName" to p.projectName, "packageName" to p.packageName, "language" to p.language, "warnings" to p.warnings,
                )
            },
            "messages" to s.messages.map { mapOf("who" to it.speaker.name, "text" to it.text, "at" to it.atMillis) },
            "logs" to s.logs.map { mapOf("at" to it.atMillis, "level" to it.level.name, "text" to it.text, "task" to it.taskId) },
            "autoCorrect" to s.options.autoCorrect,
            "staged" to s.staged.map { (path, text) -> mapOf("path" to path, "text" to text, "base" to s.baseHashes[path]) },
            "deps" to s.recommendedDependencies.map { adviceToMap(it) },
            "rejections" to s.rejections.map { mapOf("path" to it.path, "reason" to it.reason, "task" to it.taskId) },
            "corrections" to s.corrections.map { mapOf("round" to it.round, "files" to it.files, "summary" to it.summary, "before" to it.errorsBefore, "after" to it.errorsAfter, "undone" to it.undone) },
            "report" to s.reportMarkdown, "calls" to s.modelCalls, "started" to s.startedAtMillis, "finished" to s.finishedAtMillis,
            "error" to s.error, "applied" to s.appliedRecordId,
        ),
    )

    fun fromJson(text: String): AgentSnapshot? {
        val o = try { MiniJson.parseObject(text) } catch (e: Exception) { return null }
        if ((o["v"] as? Number)?.toInt() != VERSION) return null
        val plan = o.obj("plan")?.let { p ->
            AgentPlan(
                understanding = p.str("understanding").orEmpty(), assumptions = strings(p["assumptions"]), summary = p.str("summary").orEmpty(),
                architecture = p.str("architecture").orEmpty(),
                dependencies = p.arr("dependencies").orEmpty().mapNotNull { it.asObj()?.let { m -> adviceFromMap(m) } },
                tasks = p.arr("tasks").orEmpty().mapNotNull { it.asObj()?.let { m -> taskFromMap(m) } },
                risks = strings(p["risks"]), projectName = p.str("projectName"), packageName = p.str("packageName"), language = p.str("language"),
                warnings = strings(p["warnings"]),
            )
        }
        val staged = LinkedHashMap<String, String>()
        val hashes = HashMap<String, String?>()
        o.arr("staged").orEmpty().forEach { e ->
            val m = e.asObj() ?: return@forEach
            val path = m.str("path") ?: return@forEach
            staged[path] = m.str("text") ?: return@forEach
            hashes[path] = m.str("base")
        }
        return AgentSnapshot(
            runId = o.str("runId") ?: return null, goal = o.str("goal").orEmpty(),
            phase = enumOf<AgentPhase>(o.str("phase")) ?: AgentPhase.IDLE, stage = enumOf<AgentStage>(o.str("stage")), plan = plan, planApproved = o.bool("approved") ?: false,
            messages = o.arr("messages").orEmpty().mapNotNull { e ->
                val m = e.asObj() ?: return@mapNotNull null
                AgentMessage(enumOf<AgentSpeaker>(m.str("who")) ?: AgentSpeaker.USER, m.str("text") ?: return@mapNotNull null, m.long("at") ?: 0L)
            },
            logs = o.arr("logs").orEmpty().mapNotNull { e ->
                val m = e.asObj() ?: return@mapNotNull null
                AgentLogEntry(m.long("at") ?: 0L, enumOf<AgentLogLevel>(m.str("level")) ?: AgentLogLevel.INFO, m.str("text") ?: return@mapNotNull null, m.str("task"))
            },
            options = AgentOptions(autoCorrect = o.bool("autoCorrect") ?: true),
            staged = staged, baseHashes = hashes,
            recommendedDependencies = o.arr("deps").orEmpty().mapNotNull { it.asObj()?.let { m -> adviceFromMap(m) } },
            rejections = o.arr("rejections").orEmpty().mapNotNull { e ->
                val m = e.asObj() ?: return@mapNotNull null
                AgentRejection(m.str("path") ?: return@mapNotNull null, m.str("reason").orEmpty(), m.str("task"))
            },
            corrections = o.arr("corrections").orEmpty().mapNotNull { e ->
                val m = e.asObj() ?: return@mapNotNull null
                AgentCorrection(
                    (m["round"] as? Number)?.toInt() ?: 0, strings(m["files"]), m.str("summary").orEmpty(),
                    (m["before"] as? Number)?.toInt() ?: 0, (m["after"] as? Number)?.toInt() ?: 0, m.bool("undone") ?: false,
                )
            },
            reportMarkdown = o.str("report"), modelCalls = (o["calls"] as? Number)?.toInt() ?: 0,
            startedAtMillis = o.long("started") ?: 0L, finishedAtMillis = o.long("finished"), error = o.str("error"), appliedRecordId = o.str("applied"),
        )
    }
}
