package com.almus.appcreatorpro.service.ai

import com.almus.appcreatorpro.agent.AgentRunStore
import com.almus.appcreatorpro.agent.AgentSnapshot
import com.almus.appcreatorpro.agent.AgentSnapshotJson
import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.AppliedFile
import com.almus.appcreatorpro.ai.AppliedRecord
import com.almus.appcreatorpro.ai.ChangeHistoryStore
import com.almus.appcreatorpro.ai.ChatHistoryStore
import com.almus.appcreatorpro.ai.ChatTurn
import com.almus.appcreatorpro.ai.ChatTurns
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.arr
import com.almus.appcreatorpro.service.github.asObj
import com.almus.appcreatorpro.service.github.bool
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** [AiProjectFiles] for one project on top of the app's source-file storage. */
class RepositoryAiProjectFiles(private val projectId: String, private val repo: SourceFileRepository) : AiProjectFiles {
    override suspend fun list(): List<String> = repo.listFiles(projectId).map { it.path }
    override suspend fun exists(path: String): Boolean = repo.listFiles(projectId).any { it.path == path }
    override suspend fun read(path: String): String = repo.readFile(projectId, path)
    override suspend fun write(path: String, content: String) = repo.writeFile(projectId, path, content)
    override suspend fun delete(path: String) = repo.deleteFile(projectId, path)
}

private fun safeName(projectId: String): String = projectId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "project" }

private fun atomicWrite(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw java.io.IOException("Could not save ${file.name}") }
}

/** Applied AI changes, one JSON file per project in the app's private storage (kept out of cloud backups: allowBackup=false). */
class FileChangeHistoryStore(private val dir: File) : ChangeHistoryStore {
    override suspend fun load(projectId: String): List<AppliedRecord> = withContext(Dispatchers.IO) {
        val f = File(dir, safeName(projectId) + ".json")
        if (!f.isFile) return@withContext emptyList()
        try {
            MiniJson.parseObject(f.readText(Charsets.UTF_8)).arr("records").orEmpty().mapNotNull { r ->
                val o = r.asObj() ?: return@mapNotNull null
                AppliedRecord(
                    id = o.str("id") ?: return@mapNotNull null,
                    title = o.str("title").orEmpty(),
                    appliedAtMillis = o.long("at") ?: 0L,
                    undone = o.bool("undone") ?: false,
                    files = o.arr("files").orEmpty().mapNotNull { x ->
                        val fo = x.asObj() ?: return@mapNotNull null
                        AppliedFile(fo.str("path") ?: return@mapNotNull null, fo.str("before"), fo.str("after") ?: return@mapNotNull null)
                    },
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun save(projectId: String, records: List<AppliedRecord>) = withContext(Dispatchers.IO) {
        val json = MiniJson.stringify(
            mapOf(
                "records" to records.map { r ->
                    mapOf(
                        "id" to r.id, "title" to r.title, "at" to r.appliedAtMillis, "undone" to r.undone,
                        "files" to r.files.map { mapOf("path" to it.path, "before" to it.before, "after" to it.after) },
                    )
                },
            ),
        )
        atomicWrite(File(dir, safeName(projectId) + ".json"), json)
    }
}

/** Chat history, one JSON file per project in the app's private storage. */
class FileChatHistoryStore(private val dir: File) : ChatHistoryStore {
    override suspend fun load(projectId: String): List<ChatTurn> = withContext(Dispatchers.IO) {
        val f = File(dir, safeName(projectId) + ".json")
        if (!f.isFile) return@withContext emptyList()
        try { ChatTurns.fromJson(f.readText(Charsets.UTF_8)) } catch (e: Exception) { emptyList() }
    }

    override suspend fun save(projectId: String, turns: List<ChatTurn>) = withContext(Dispatchers.IO) {
        atomicWrite(File(dir, safeName(projectId) + ".json"), ChatTurns.toJson(turns))
    }

    override suspend fun clear(projectId: String) = withContext(Dispatchers.IO) {
        File(dir, safeName(projectId) + ".json").delete()
        Unit
    }
}

/** The last agent run of each project (plan, task memory, staged files, log), one JSON file per project in private storage. */
class FileAgentRunStore(private val dir: File) : AgentRunStore {
    private fun file(projectId: String) = File(dir, safeName(projectId) + ".json")

    override suspend fun load(projectId: String): AgentSnapshot? = withContext(Dispatchers.IO) {
        val f = file(projectId)
        if (!f.isFile) return@withContext null
        try { AgentSnapshotJson.fromJson(f.readText(Charsets.UTF_8)) } catch (e: Exception) { null }
    }

    override suspend fun save(projectId: String, snapshot: AgentSnapshot) = withContext(Dispatchers.IO) {
        atomicWrite(file(projectId), AgentSnapshotJson.toJson(snapshot))
    }

    override suspend fun clear(projectId: String) = withContext(Dispatchers.IO) {
        file(projectId).delete()
        Unit
    }
}
