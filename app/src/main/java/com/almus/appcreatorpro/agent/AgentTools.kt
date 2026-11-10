package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.ContextPolicy
import com.almus.appcreatorpro.ai.SecretScanner

data class ToolOutput(val tool: String, val text: String, val error: Boolean, val summary: String)

/**
 * Tool calling for the agent. The model may ask, in its JSON answer, to list, read or search project files; the agent runs
 * the call here and sends the result back in the next message. All tools are READ-ONLY and go through the same rules as
 * the files the user confirms for chat: no secret files, no build output, secret-looking values replaced, size limits.
 * They read the agent's working copy, so a file created by an earlier task can be read too.
 *
 * [readable] collects every path the model has now seen; the staging step only lets the agent change existing files from
 * that set. [truncated] holds files that were shown only in part.
 */
class AgentToolbox(
    private val files: AiProjectFiles,
    private val perms: AgentPermissions,
    val readable: MutableSet<String>,
    val truncated: MutableSet<String>,
) {
    private var reads = 0

    suspend fun run(calls: List<ToolCall>): List<ToolOutput> {
        val out = ArrayList<ToolOutput>()
        var budget = MAX_RESULT_CHARS
        for (c in calls) {
            var r = runOne(c)
            if (r.text.length > budget) r = r.copy(text = r.text.take(maxOf(0, budget)) + "\n[result cut: too long]")
            budget -= r.text.length
            out += r
        }
        return out
    }

    private suspend fun runOne(c: ToolCall): ToolOutput {
        if (c.tool !in perms.allowedTools) return fail(c.tool, "The tool \"${c.tool.take(30)}\" is not available. Available: ${perms.allowedTools.sorted().joinToString()}.")
        return try {
            when (c.tool) {
                LIST_FILES -> list(c.args["prefix"].orEmpty())
                READ_FILE -> read(c.args["path"].orEmpty())
                SEARCH -> search(c.args["query"].orEmpty())
                else -> fail(c.tool, "Unknown tool.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(c.tool, "The tool failed (${e.javaClass.simpleName}).")
        }
    }

    private fun fail(tool: String, message: String) = ToolOutput(tool, "ERROR: $message", error = true, summary = message)

    private suspend fun visiblePaths(): List<String> =
        files.list().filter { !ContextPolicy.isHiddenPath(it) && !SecretScanner.isSensitivePath(it) }

    private suspend fun list(prefix: String): ToolOutput {
        val all = visiblePaths().filter { it.startsWith(prefix.trim()) }
        val shown = all.take(ContextPolicy.MAX_STRUCTURE_PATHS)
        val text = shown.joinToString("\n") + if (all.size > shown.size) "\n... and ${all.size - shown.size} more" else ""
        return ToolOutput(LIST_FILES, text.ifEmpty { "(no files)" }, false, "${all.size} file(s)")
    }

    private suspend fun read(path: String): ToolOutput {
        val p = path.trim()
        if (p.isEmpty()) return fail(READ_FILE, "read_file needs a \"path\".")
        if (reads >= perms.maxFilesReadPerTask) return fail(READ_FILE, "At most ${perms.maxFilesReadPerTask} files can be read for one task.")
        if (p !in files.list().toHashSet()) return fail(READ_FILE, "No such file: ${p.take(120)}.")
        if (SecretScanner.isSensitivePath(p)) return fail(READ_FILE, "This file looks like it holds secrets, so it is never read.")
        if (ContextPolicy.isHiddenPath(p)) return fail(READ_FILE, "Build output and tool folders are not available.")
        if (!ContextPolicy.isTextPath(p)) return fail(READ_FILE, "Only text source files can be read.")
        val raw = files.read(p)
        val red = SecretScanner.redact(raw)
        val cut = red.text.length > ContextPolicy.MAX_FILE_CHARS
        val text = if (cut) red.text.take(ContextPolicy.MAX_FILE_CHARS) else red.text
        reads++
        readable += p
        if (cut) truncated += p
        return ToolOutput(READ_FILE, "=== FILE: $p${if (cut) " [truncated]" else ""} ===\n$text\n=== END FILE ===", false, "read $p (${text.length} chars)")
    }

    private suspend fun search(query: String): ToolOutput {
        val q = query.trim()
        if (q.length < 2) return fail(SEARCH, "search needs a \"query\" of at least 2 characters.")
        val hits = ArrayList<String>()
        var scanned = 0
        for (p in visiblePaths()) {
            if (hits.size >= MAX_HITS || scanned >= MAX_SCANNED) break
            if (!ContextPolicy.isTextPath(p)) continue
            scanned++
            val text = try { files.read(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { continue }
            var lineNo = 0
            for (line in text.lineSequence()) {
                lineNo++
                if (line.contains(q, ignoreCase = true)) {
                    hits += "$p:$lineNo: ${SecretScanner.redact(line.trim()).text.take(160)}"
                    if (hits.size >= MAX_HITS) break
                }
            }
        }
        return ToolOutput(SEARCH, hits.joinToString("\n").ifEmpty { "(no matches)" }, false, "${hits.size} match(es)")
    }

    companion object {
        const val LIST_FILES = "list_files"
        const val READ_FILE = "read_file"
        const val SEARCH = "search"
        const val MAX_RESULT_CHARS = 40_000
        const val MAX_HITS = 30
        const val MAX_SCANNED = 300
    }
}
