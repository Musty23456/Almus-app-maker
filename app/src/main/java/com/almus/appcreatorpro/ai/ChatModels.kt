package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.editor.Language
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.arr
import com.almus.appcreatorpro.service.github.asObj
import com.almus.appcreatorpro.service.github.bool
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.str

/** What the user wants from the chat. Each mode is a backend task with its own instructions. */
enum class ChatMode(val label: String, val task: AiTaskKind, val hint: String) {
    ASK("Ask", AiTaskKind.CHAT, "Ask anything about your app or Android"),
    EXPLAIN("Explain code", AiTaskKind.EXPLAIN, "Attach files or paste code to explain"),
    GENERATE("Generate code", AiTaskKind.GENERATE_CODE, "Describe the code to write"),
    EDIT("Edit code", AiTaskKind.EDIT_CODE, "Attach a file and describe the change"),
}

enum class TurnState { DONE, STREAMING, ERROR, CANCELLED }

data class ChatTurn(
    val id: String,
    val role: AiRole,
    val text: String,
    val state: TurnState = TurnState.DONE,
    val error: String? = null,
    val retryable: Boolean = false,
    /** For user turns: how to answer it again ("regenerate"). */
    val mode: ChatMode? = null,
    val contextPaths: List<String> = emptyList(),
    val createdAtMillis: Long = 0L,
    val model: String? = null,
)

data class ChatState(val turns: List<ChatTurn> = emptyList(), val busy: Boolean = false, val notice: String? = null)

interface ChatHistoryStore {
    suspend fun load(projectId: String): List<ChatTurn>
    suspend fun save(projectId: String, turns: List<ChatTurn>)
    suspend fun clear(projectId: String)
}

class InMemoryChatHistoryStore : ChatHistoryStore {
    private val map = HashMap<String, List<ChatTurn>>()
    override suspend fun load(projectId: String): List<ChatTurn> = map[projectId].orEmpty()
    override suspend fun save(projectId: String, turns: List<ChatTurn>) { map[projectId] = turns }
    override suspend fun clear(projectId: String) { map.remove(projectId) }
}

object ChatTurns {
    const val MAX_STORED_TURNS = 200

    fun toJson(turns: List<ChatTurn>): String = MiniJson.stringify(
        mapOf(
            "turns" to turns.takeLast(MAX_STORED_TURNS).map { t ->
                mapOf(
                    "id" to t.id, "role" to t.role.wire, "text" to t.text, "state" to t.state.name, "error" to t.error,
                    "retryable" to t.retryable, "mode" to t.mode?.name, "paths" to t.contextPaths, "at" to t.createdAtMillis, "model" to t.model,
                )
            },
        ),
    )

    fun fromJson(json: String): List<ChatTurn> =
        MiniJson.parseObject(json).arr("turns").orEmpty().mapNotNull { x ->
            val o = x.asObj() ?: return@mapNotNull null
            val role = AiRole.entries.firstOrNull { it.wire == o.str("role") } ?: return@mapNotNull null
            ChatTurn(
                id = o.str("id") ?: return@mapNotNull null,
                role = role,
                text = o.str("text").orEmpty(),
                // A turn that was still streaming when the app was closed is incomplete.
                state = TurnState.entries.firstOrNull { it.name == o.str("state") }?.let { if (it == TurnState.STREAMING) TurnState.CANCELLED else it } ?: TurnState.DONE,
                error = o.str("error"),
                retryable = o.bool("retryable") ?: false,
                mode = ChatMode.entries.firstOrNull { it.name == o.str("mode") },
                contextPaths = o.arr("paths").orEmpty().mapNotNull { it as? String },
                createdAtMillis = o.long("at") ?: 0L,
                model = o.str("model"),
            )
        }
}

/** Builds the messages sent to the backend from the visible conversation. */
object ChatMessageBuilder {
    const val HISTORY_CHAR_BUDGET = 60_000

    /**
     * [turns] ends with the user turn being answered. Failed, cancelled and empty answers are left out, the newest turns
     * win when the budget is short, same-role neighbours are merged, and the file context is attached only to the last
     * user message (old file contents are not sent again with every question).
     */
    fun build(turns: List<ChatTurn>, latestContext: String?): List<AiMessage> {
        val usable = turns.filterIndexed { i, t ->
            i == turns.lastIndex || t.role == AiRole.USER || (t.state == TurnState.DONE && t.text.isNotBlank())
        }
        val picked = ArrayList<ChatTurn>()
        var used = 0
        for (t in usable.asReversed()) {
            val cost = t.text.length
            if (picked.isNotEmpty() && used + cost > HISTORY_CHAR_BUDGET) break
            picked += t
            used += cost
        }
        picked.reverse()
        while (picked.size > 1 && picked.first().role != AiRole.USER) picked.removeAt(0)

        val out = ArrayList<AiMessage>()
        picked.forEachIndexed { i, t ->
            var content = t.text
            if (i == picked.lastIndex && !latestContext.isNullOrBlank()) content = content + "\n\n" + latestContext
            val last = out.lastOrNull()
            if (last != null && last.role == t.role) out[out.lastIndex] = last.copy(content = last.content + "\n\n" + content)
            else out += AiMessage(t.role, content)
        }
        return out
    }
}

/** Splits a chat answer into text and code blocks (Markdown fences), tolerating a block that is still streaming. */
object ChatMarkdown {
    sealed interface Block {
        data class Text(val text: String) : Block

        /** [path] is set when the line before the block said "File: some/path". */
        data class Code(val language: String?, val code: String, val closed: Boolean, val path: String?) : Block
    }

    private val pathLabel = Regex("^\\W*(?:file|path)[\\s*]*:[\\s*]*`?([\\w./\\- ]+?)`?\\W*$", RegexOption.IGNORE_CASE)

    fun split(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        val prose = ArrayList<String>()
        val code = ArrayList<String>()
        var inCode = false
        var lang: String? = null
        var path: String? = null

        fun flushProse() {
            val joined = prose.joinToString("\n").trim('\n')
            if (joined.isNotBlank()) blocks += Block.Text(joined)
            prose.clear()
        }

        for (line in text.split("\n")) {
            val t = line.trim()
            if (inCode) {
                if (t.startsWith("```")) {
                    blocks += Block.Code(lang, code.joinToString("\n"), true, path)
                    code.clear(); inCode = false; lang = null; path = null
                } else code += line
            } else if (t.startsWith("```")) {
                var found: String? = null
                val lastIdx = prose.indexOfLast { it.isNotBlank() }
                if (lastIdx >= 0) {
                    val m = pathLabel.find(prose[lastIdx].trim())
                    if (m != null) { found = m.groupValues[1].trim(); prose.removeAt(lastIdx) }
                }
                flushProse()
                inCode = true
                lang = t.removePrefix("```").trim().lowercase().takeIf { it.isNotEmpty() }
                path = found
            } else prose += line
        }
        if (inCode) blocks += Block.Code(lang, code.joinToString("\n"), false, path) else flushProse()
        return blocks
    }

    fun languageFor(tag: String?, path: String?): Language {
        val fromTag = when (tag) {
            "kotlin", "kt" -> "x.kt"
            "kts", "gradle" -> "x.kts"
            "java" -> "x.java"
            "xml" -> "x.xml"
            "json" -> "x.json"
            "js", "javascript" -> "x.js"
            "css" -> "x.css"
            "html" -> "x.html"
            "md", "markdown" -> "x.md"
            "python", "py" -> "x.py"
            "typescript", "ts", "tsx" -> "x.ts"
            "dart" -> "x.dart"
            "yaml", "yml" -> "x.yml"
            "sql" -> "x.sql"
            else -> null
        }
        return when {
            fromTag != null -> Language.fromFileName(fromTag)
            path != null -> Language.fromFileName(path)
            else -> Language.PLAIN
        }
    }
}

/** "Apply to file" for chat answers: the code block becomes a reviewable change, never a silent overwrite. */
object ChatApply {
    suspend fun changeSet(turn: ChatTurn, files: AiProjectFiles, nowMillis: Long): ResolveResult? {
        val blocks = ChatMarkdown.split(turn.text).filterIsInstance<ChatMarkdown.Block.Code>().filter { it.closed && it.code.isNotBlank() }
        if (blocks.isEmpty()) return null
        val single = turn.contextPaths.singleOrNull()
        val changes = ArrayList<RawChange>()
        for ((i, b) in blocks.withIndex()) {
            val target = b.path ?: if (i == 0) single else null
            if (target != null) changes += RawChange(target, "modify", "From the AI chat", b.code.trimEnd('\n') + "\n", emptyList())
        }
        if (changes.isEmpty()) return null
        return ChangeResolver.fromProposal(EditProposal("From the AI chat", changes), files, "AI chat change", nowMillis)
    }
}
