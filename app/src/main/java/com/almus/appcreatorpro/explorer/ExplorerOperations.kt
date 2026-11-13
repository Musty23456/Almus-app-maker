package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface OpResult {
    /** [moved] maps old path -> new path, [deleted] lists removed paths, [created] is a new file's path. */
    data class Done(
        val message: String,
        val created: String? = null,
        val moved: Map<String, String> = emptyMap(),
        val deleted: List<String> = emptyList(),
    ) : OpResult

    data class Failed(val message: String) : OpResult

    /** Nothing was changed: the caller must ask the user and call again with the confirmation flag. */
    data class NeedsConfirmation(val message: String, val protectedPaths: List<String>) : OpResult
}

/** What a delete would remove. Built before anything is deleted so the UI can show it and ask. */
data class DeletePlan(
    val path: String,
    val isFolder: Boolean,
    val files: List<String>,
    val totalBytes: Long,
    val protectedFiles: List<String>,
    /** Open files with unsaved typing that would be lost (filled in by the editor). */
    val unsaved: List<String> = emptyList(),
) {
    /** Important configuration files are inside: the user must type [confirmationWord]. */
    val needsTypedConfirmation: Boolean get() = protectedFiles.isNotEmpty()
    val confirmationWord: String get() = path.substringAfterLast('/')
}

/**
 * Every file operation of the explorer. It only talks to [SourceFileRepository], which is bound to ONE project's
 * folder and re-checks every path (no "..", no absolute paths, canonical path must stay inside the project). On top
 * of that this class validates each path itself, never overwrites, rolls back multi-file changes that fail halfway
 * and asks for confirmation before touching important configuration files.
 */
class ExplorerOperations(
    private val projectId: String,
    private val repo: SourceFileRepository,
) {

    private suspend fun attempt(block: suspend () -> OpResult): OpResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        OpResult.Failed(e.toUserMessage())
    }

    private suspend fun currentFiles(): Map<String, Long> = repo.listFiles(projectId).associate { it.path to it.sizeBytes }

    // ------------------------------------------------------------------ create

    suspend fun createFile(rawPath: String): OpResult = attempt {
        val path = PathOps.normalizeInput(rawPath)
        PathOps.validateFile(path)?.let { return@attempt OpResult.Failed(it) }
        val files = currentFiles()
        if (path in files) return@attempt OpResult.Failed("A file with this path already exists.")
        if (files.keys.any { it.startsWith("$path/") }) return@attempt OpResult.Failed("A folder with this name already exists.")
        repo.createFile(projectId, path, "")
        OpResult.Done("Created $path", created = path)
    }

    /** Folders only exist while they hold a file, so a new folder gets an empty ".gitkeep". */
    suspend fun createFolder(rawPath: String): OpResult = attempt {
        val folder = PathOps.normalizeInput(rawPath).trimEnd('/')
        PathOps.validateFolder(folder)?.let { return@attempt OpResult.Failed(it) }
        val marker = PathOps.join(folder, KEEP_FILE)
        PathOps.validateFile(marker)?.let { return@attempt OpResult.Failed(it) }
        val files = currentFiles()
        if (folder in files) return@attempt OpResult.Failed("A file with this name already exists.")
        if (files.keys.any { it.startsWith("$folder/") }) return@attempt OpResult.Failed("This folder already exists.")
        repo.createFile(projectId, marker, "")
        OpResult.Done("Created folder $folder/", created = marker)
    }

    // ------------------------------------------------------------------ duplicate

    suspend fun duplicate(path: String): OpResult = attempt {
        PathOps.validateFile(path)?.let { return@attempt OpResult.Failed(it) }
        val files = currentFiles()
        if (path !in files) return@attempt OpResult.Failed("'$path' no longer exists.")
        val target = PathOps.duplicateName(path, files.keys)
            ?: return@attempt OpResult.Failed("Could not find a free name for the copy.")
        val text = repo.readFile(projectId, path)
        repo.createFile(projectId, target, text)
        OpResult.Done("Duplicated to $target", created = target)
    }

    // ------------------------------------------------------------------ rename / move

    suspend fun rename(from: String, rawTo: String, confirmedImportant: Boolean = false): OpResult = attempt {
        val to = PathOps.normalizeInput(rawTo)
        PathOps.validateFile(from)?.let { return@attempt OpResult.Failed(it) }
        PathOps.validateFile(to)?.let { return@attempt OpResult.Failed(it) }
        if (from == to) return@attempt OpResult.Failed("Enter a different name.")
        val files = currentFiles()
        if (from !in files) return@attempt OpResult.Failed("'$from' no longer exists.")
        if (to in files) return@attempt OpResult.Failed("A file with this path already exists.")
        if (files.keys.any { it.startsWith("$to/") }) return@attempt OpResult.Failed("A folder with this name already exists.")
        if (ProtectedFiles.isProtected(from) && !confirmedImportant) {
            return@attempt OpResult.NeedsConfirmation(
                "\"$from\" is an important file (${ProtectedFiles.check(from).reason}). Renaming or moving it can break the project.",
                listOf(from),
            )
        }
        repo.renameFile(projectId, from, to)
        OpResult.Done("Renamed to $to", moved = mapOf(from to to))
    }

    /** Renames or moves a whole folder by moving each file; if one move fails, the ones already done are undone. */
    suspend fun renameFolder(rawFrom: String, rawTo: String, confirmedImportant: Boolean = false): OpResult = attempt {
        val from = PathOps.normalizeInput(rawFrom).trimEnd('/')
        val to = PathOps.normalizeInput(rawTo).trimEnd('/')
        PathOps.validateFolder(from)?.let { return@attempt OpResult.Failed(it) }
        PathOps.validateFolder(to)?.let { return@attempt OpResult.Failed(it) }
        if (from == to) return@attempt OpResult.Failed("Enter a different name.")
        if (PathOps.isInside(from, to)) return@attempt OpResult.Failed("A folder cannot be moved into itself.")
        val files = currentFiles()
        val sources = files.keys.filter { PathOps.isInside(from, it) }.sorted()
        if (sources.isEmpty()) return@attempt OpResult.Failed("'$from' no longer exists.")
        val plan = LinkedHashMap<String, String>()
        for (source in sources) {
            val target = PathOps.moveUnder(from, to, source)!!
            PathOps.validateFile(target)?.let { return@attempt OpResult.Failed("$target: $it") }
            if (target in files) return@attempt OpResult.Failed("'$target' already exists.")
            if (PathOps.ancestors(target).any { it in files }) {
                return@attempt OpResult.Failed("A file is in the way of the folder '${PathOps.parent(target)}'.")
            }
            plan[source] = target
        }
        if (to in files) return@attempt OpResult.Failed("A file named '$to' already exists.")
        val important = ProtectedFiles.protectedIn(sources)
        if (important.isNotEmpty() && !confirmedImportant) {
            return@attempt OpResult.NeedsConfirmation(
                "The folder \"$from\" holds important configuration files. Moving it can break the project.",
                important,
            )
        }
        val done = ArrayList<Pair<String, String>>()
        try {
            for ((source, target) in plan) {
                repo.renameFile(projectId, source, target)
                done += source to target
            }
        } catch (e: CancellationException) {
            // Even when cancelled, put the files back: a half-moved folder must never be left behind.
            withContext(NonCancellable) { undoMoves(done) }
            throw e
        } catch (e: Exception) {
            val stuck = undoMoves(done)
            val tail = if (stuck.isEmpty()) "Nothing was changed." else "Could not move back: ${stuck.joinToString()}."
            return@attempt OpResult.Failed("${e.toUserMessage()} $tail")
        }
        OpResult.Done("Renamed folder to $to/", moved = plan)
    }

    /** Moves files back in reverse order; returns the paths that could not be moved back. */
    private suspend fun undoMoves(done: List<Pair<String, String>>): List<String> {
        val stuck = ArrayList<String>()
        for ((source, target) in done.asReversed()) {
            try {
                repo.renameFile(projectId, target, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stuck += target
            }
        }
        return stuck
    }

    // ------------------------------------------------------------------ delete

    /** Describes what deleting [path] would remove; null when it does not exist. Deletes nothing. */
    suspend fun planDelete(path: String, isFolder: Boolean): DeletePlan? {
        val files = repo.listFiles(projectId)
        val hit = if (isFolder) files.filter { PathOps.isInside(path, it.path) } else files.filter { it.path == path }
        if (hit.isEmpty()) return null
        val paths = hit.map { it.path }.sorted()
        return DeletePlan(path, isFolder, paths, hit.sumOf { it.sizeBytes }, ProtectedFiles.protectedIn(paths))
    }

    /**
     * Deletes what [plan] lists. Needs the typed confirmation word when important files are inside. A folder is
     * deleted only after every file was backed up in memory; if a delete fails halfway the deleted files are restored.
     */
    suspend fun delete(plan: DeletePlan, typedConfirmation: String?): OpResult = attempt {
        if (plan.needsTypedConfirmation && typedConfirmation?.trim() != plan.confirmationWord) {
            return@attempt OpResult.Failed("Type \"${plan.confirmationWord}\" exactly to confirm.")
        }
        val files = currentFiles()
        val targets = plan.files.filter { it in files }
        if (targets.isEmpty()) return@attempt OpResult.Failed("'${plan.path}' no longer exists.")
        for (p in targets) PathOps.validateFile(p)?.let { return@attempt OpResult.Failed(it) }
        val backup = LinkedHashMap<String, String>()
        if (targets.size > 1) {
            for (p in targets) {
                backup[p] = try {
                    repo.readFile(projectId, p)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@attempt OpResult.Failed(
                        "'$p' cannot be backed up first (${e.toUserMessage()}), so nothing was deleted. Delete its files one by one.",
                    )
                }
            }
        }
        val deleted = ArrayList<String>()
        try {
            for (p in targets) {
                repo.deleteFile(projectId, p)
                deleted += p
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { restore(deleted, backup) }
            throw e
        } catch (e: Exception) {
            val lost = restore(deleted, backup)
            val tail = if (lost.isEmpty()) "Nothing was deleted." else "Could not restore: ${lost.joinToString()}."
            return@attempt OpResult.Failed("${e.toUserMessage()} $tail")
        }
        val what = if (plan.isFolder) "folder ${plan.path}/" else plan.path.substringAfterLast('/')
        OpResult.Done("Deleted $what", deleted = deleted)
    }

    private suspend fun restore(deleted: List<String>, backup: Map<String, String>): List<String> {
        val lost = ArrayList<String>()
        for (p in deleted) {
            val text = backup[p]
            if (text == null) {
                continue
            }
            try {
                repo.createFile(projectId, p, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lost += p
            }
        }
        return lost
    }

    companion object {
        const val KEEP_FILE = ".gitkeep"
    }
}
