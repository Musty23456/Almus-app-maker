package com.almus.appcreatorpro.data.repository

import com.almus.appcreatorpro.domain.error.StorageException
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.repository.BuildStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val MAX_RECORDS = 100

private fun List<BuildRecord>.newestFirst() = sortedByDescending { it.startedAtMillis }

/** Keeps at most [MAX_RECORDS]; only finished builds are ever dropped. */
private fun List<BuildRecord>.pruned(): List<BuildRecord> {
    if (size <= MAX_RECORDS) return this
    val sorted = newestFirst()
    val keepActive = sorted.filter { !it.status.isTerminal }
    val finished = sorted.filter { it.status.isTerminal }.take((MAX_RECORDS - keepActive.size).coerceAtLeast(0))
    return (keepActive + finished).newestFirst()
}

/** Non-persistent store for tests and previews. */
class InMemoryBuildStore(initial: List<BuildRecord> = emptyList()) : BuildStore {
    private val state = MutableStateFlow(initial.newestFirst())

    override fun observeBuilds(): Flow<List<BuildRecord>> = state
    override suspend fun getBuildHistory(): List<BuildRecord> = state.value
    override suspend fun getBuild(id: String): BuildRecord? = state.value.firstOrNull { it.id == id }
    override suspend fun saveBuild(record: BuildRecord) {
        state.value = (state.value.filter { it.id != record.id } + record).pruned().newestFirst()
    }
    override suspend fun deleteBuild(id: String) {
        state.value = state.value.filter { it.id != id }
    }
}

/**
 * One JSON file per build in [dir] (inside the app's private storage). Writes are atomic (temp file, then move);
 * damaged files are skipped so they cannot break the history screen.
 */
class FileBuildStore(
    private val dir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BuildStore {
    private val state = MutableStateFlow<List<BuildRecord>?>(null)
    private val lock = Any()

    override fun observeBuilds(): Flow<List<BuildRecord>> = state.map { it ?: loadOnce() }.flowOn(ioDispatcher)

    override suspend fun getBuildHistory(): List<BuildRecord> = withContext(ioDispatcher) { loadOnce() }

    override suspend fun getBuild(id: String): BuildRecord? = withContext(ioDispatcher) { loadOnce().firstOrNull { it.id == id } }

    override suspend fun saveBuild(record: BuildRecord) = withContext(ioDispatcher) {
        synchronized(lock) {
            try {
                dir.mkdirs()
                val target = fileFor(record.id)
                val temp = File(dir, "${record.id}.json.tmp")
                temp.writeText(BuildRecordJson.toJson(record), Charsets.UTF_8)
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                throw StorageException(StorageException.Kind.IO, "Could not save the build record.", e)
            }
            val before = loadOnce()
            val after = (before.filter { it.id != record.id } + record).pruned().newestFirst()
            (before.map { it.id }.toSet() - after.map { it.id }.toSet()).forEach { fileFor(it).delete() }
            state.value = after
        }
    }

    override suspend fun deleteBuild(id: String) = withContext(ioDispatcher) {
        synchronized(lock) {
            fileFor(id).delete()
            state.value = loadOnce().filter { it.id != id }
        }
    }

    private fun loadOnce(): List<BuildRecord> = synchronized(lock) {
        state.value ?: run {
            val loaded = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
                .mapNotNull { f -> runCatching { BuildRecordJson.fromJson(f.readText(Charsets.UTF_8)) }.getOrNull() }
                .newestFirst()
            state.value = loaded
            loaded
        }
    }

    private fun fileFor(id: String): File {
        require(id.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) { "Invalid build id" }
        return File(dir, "$id.json")
    }
}
