package com.almus.appcreatorpro.service.repair

import com.almus.appcreatorpro.repair.RepairBackupStore
import com.almus.appcreatorpro.repair.RepairEvent
import com.almus.appcreatorpro.repair.RepairLogEntry
import com.almus.appcreatorpro.repair.RepairLogStore
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.arr
import com.almus.appcreatorpro.service.github.asObj
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun safeName(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "project" }

private fun atomicWrite(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw java.io.IOException("Could not save ${file.name}") }
}

/** The repair record, one JSON file per project in the app's private storage (not copied to cloud backups: allowBackup=false). */
class FileRepairLogStore(private val dir: File) : RepairLogStore {
    override suspend fun load(projectId: String): List<RepairLogEntry> = withContext(Dispatchers.IO) {
        val f = File(dir, safeName(projectId) + ".json")
        if (!f.isFile) return@withContext emptyList()
        try {
            MiniJson.parseObject(f.readText(Charsets.UTF_8)).arr("entries").orEmpty().mapNotNull { x ->
                val o = x.asObj() ?: return@mapNotNull null
                val event = try { RepairEvent.valueOf(o.str("event") ?: return@mapNotNull null) } catch (e: IllegalArgumentException) { return@mapNotNull null }
                RepairLogEntry(
                    id = o.str("id") ?: return@mapNotNull null,
                    atMillis = o.long("at") ?: 0L,
                    event = event,
                    planId = o.str("plan").orEmpty(),
                    applyId = o.str("apply"),
                    issueId = o.str("issue").orEmpty(),
                    issueSummary = o.str("summary").orEmpty(),
                    path = o.str("path") ?: return@mapNotNull null,
                    origin = o.str("origin").orEmpty(),
                    title = o.str("title").orEmpty(),
                    added = (o.long("added") ?: 0L).toInt(),
                    removed = (o.long("removed") ?: 0L).toInt(),
                    beforeHash = o.str("beforeHash"),
                    afterHash = o.str("afterHash"),
                    proposalIds = o.arr("proposals").orEmpty().mapNotNull { it as? String },
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun save(projectId: String, entries: List<RepairLogEntry>) = withContext(Dispatchers.IO) {
        val json = MiniJson.stringify(
            mapOf(
                "entries" to entries.map { e ->
                    mapOf(
                        "id" to e.id, "at" to e.atMillis, "event" to e.event.name, "plan" to e.planId, "apply" to e.applyId,
                        "issue" to e.issueId, "summary" to e.issueSummary, "path" to e.path, "origin" to e.origin, "title" to e.title,
                        "added" to e.added, "removed" to e.removed, "beforeHash" to e.beforeHash, "afterHash" to e.afterHash,
                        "proposals" to e.proposalIds,
                    )
                },
            ),
        )
        atomicWrite(File(dir, safeName(projectId) + ".json"), json)
    }
}

/**
 * The original text of the files of each applied repair: one JSON file per repair, `<project>/<applyId>.json`.
 * Written before the project is touched and never overwritten or pruned by the app, so a rollback stays possible.
 */
class FileRepairBackupStore(private val dir: File) : RepairBackupStore {
    private fun file(projectId: String, applyId: String) = File(File(dir, safeName(projectId)), safeName(applyId) + ".json")

    override suspend fun save(projectId: String, applyId: String, files: Map<String, String>) = withContext(Dispatchers.IO) {
        val target = file(projectId, applyId)
        // Never replace an existing backup: it holds the original of an earlier write.
        if (target.exists()) return@withContext
        val json = MiniJson.stringify(mapOf("files" to files.map { (path, text) -> mapOf("path" to path, "content" to text) }))
        atomicWrite(target, json)
    }

    override suspend fun load(projectId: String, applyId: String): Map<String, String>? = withContext(Dispatchers.IO) {
        val f = file(projectId, applyId)
        if (!f.isFile) return@withContext null
        try {
            val out = LinkedHashMap<String, String>()
            for (x in MiniJson.parseObject(f.readText(Charsets.UTF_8)).arr("files").orEmpty()) {
                val o = x.asObj() ?: continue
                val path = o.str("path") ?: continue
                out[path] = o.str("content") ?: continue
            }
            out
        } catch (e: Exception) { null }
    }
}
