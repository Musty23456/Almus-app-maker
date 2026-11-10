package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.ChangeResolver
import com.almus.appcreatorpro.ai.GeneratedFile
import com.almus.appcreatorpro.ai.GeneratedProject
import com.almus.appcreatorpro.ai.ResolveResult
import com.almus.appcreatorpro.debug.TextEdits

/**
 * The agent's working copy: an overlay on top of the real project. Reads see the agent's files first, so later tasks
 * build on earlier ones. Writes go ONLY into the overlay: the real [base] is never written, renamed or deleted here, so
 * nothing in the project changes until the user applies the result through the change manager.
 *
 * For every staged file the hash of the real file at the time it was first staged is remembered ([baseHashes]). If the
 * real file changes later, [staleAgainstBase] reports it and the agent's version must not be applied over it.
 */
class StagedProjectFiles(private val base: AiProjectFiles) : AiProjectFiles {
    private val staged = LinkedHashMap<String, String>()
    private val baseHash = HashMap<String, String?>()

    override suspend fun list(): List<String> = (base.list() + staged.keys).distinct().sorted()

    override suspend fun exists(path: String): Boolean = path in staged || base.exists(path)

    override suspend fun read(path: String): String = staged[path] ?: base.read(path)

    override suspend fun write(path: String, content: String) {
        if (path !in baseHash) baseHash[path] = if (base.exists(path)) TextEdits.hash(base.read(path)) else null
        staged[path] = content
    }

    /** The agent never deletes. */
    override suspend fun delete(path: String) { throw UnsupportedOperationException("The agent never deletes files.") }

    fun isStaged(path: String): Boolean = path in staged
    fun stagedPaths(): List<String> = staged.keys.toList()
    fun stagedText(path: String): String? = staged[path]
    fun totalStagedBytes(): Int = staged.values.sumOf { it.toByteArray(Charsets.UTF_8).size }
    fun baseHashes(): Map<String, String?> = baseHash.filterKeys { it in staged }

    /** Takes one file out of the result; the real file (if any) was never touched. */
    fun discard(path: String) { staged.remove(path); baseHash.remove(path) }

    fun clear() { staged.clear(); baseHash.clear() }

    class Snapshot(val content: Map<String, String>, val hashes: Map<String, String?>)

    fun snapshot(): Snapshot = Snapshot(LinkedHashMap(staged), HashMap(baseHash))

    fun restore(s: Snapshot) { restore(s.content, s.hashes) }

    fun restore(content: Map<String, String>, hashes: Map<String, String?>) {
        staged.clear(); baseHash.clear()
        staged.putAll(content)
        for (p in content.keys) baseHash[p] = hashes[p]
    }

    /** Staged files whose real counterpart is no longer what it was when the agent started on it. */
    suspend fun staleAgainstBase(): List<String> {
        val out = ArrayList<String>()
        val known = base.list().toHashSet()
        for (p in staged.keys) {
            val expected = baseHash[p]
            val current: String? = if (p in known) {
                try { TextEdits.hash(base.read(p)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "unreadable" }
            } else null
            if (current != expected) out += p
        }
        return out
    }

    /** What applying the overlay would do to the real files. Unchanged files drop out; the real project is only read. */
    suspend fun buildChangeSet(runId: String, title: String, summary: String, nowMillis: Long): ResolveResult {
        if (staged.isEmpty()) return ResolveResult(null, emptyList())
        val project = GeneratedProject(runId, "com.example.agent", summary, staged.map { GeneratedFile(it.key, it.value) }, emptyList())
        val r = ChangeResolver.fromGenerated(project, base, nowMillis)
        val set = r.changeSet ?: return r
        return r.copy(changeSet = set.copy(title = title, summary = summary))
    }
}
