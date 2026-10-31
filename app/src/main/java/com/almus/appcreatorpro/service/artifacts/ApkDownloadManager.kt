package com.almus.appcreatorpro.service.artifacts

import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloadException.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Identifies one artifact of one build. */
data class DownloadKey(val buildId: String, val artifactId: Long)

sealed interface DownloadState {
    data class Active(val progress: DownloadProgress) : DownloadState
    data class Finished(val apks: List<StoredApk>) : DownloadState
    data class Failed(val kind: Kind, val message: String, val retryable: Boolean) : DownloadState
    data object Cancelled : DownloadState
}

sealed interface DownloadStart {
    data object Started : DownloadStart

    /** Prevents duplicates: a download of this artifact is already in progress. */
    data object AlreadyRunning : DownloadStart
    data class NotAvailable(val reason: String) : DownloadStart
}

/**
 * Runs artifact downloads in an app-wide scope, so leaving the screen does not lose a download in progress, and
 * keeps their state for the UI. At most one download per artifact runs at a time.
 */
class ApkDownloadManager(
    private val downloader: ArtifactDownloader,
    private val store: ApkStore,
    private val builds: BuildStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val jobs = HashMap<DownloadKey, Job>()
    private val _states = MutableStateFlow<Map<DownloadKey, DownloadState>>(emptyMap())

    /** Current state of every download started or finished in this app session. */
    val states: StateFlow<Map<DownloadKey, DownloadState>> = _states.asStateFlow()

    fun isActive(key: DownloadKey): Boolean = synchronized(lock) { jobs[key]?.isActive == true }

    /** Starts downloading [artifact] of [record]. Does nothing (AlreadyRunning) if that artifact is already downloading. */
    fun start(record: BuildRecord, artifact: BuildArtifactInfo): DownloadStart {
        val full = record.repoFullName.orEmpty()
        val owner = full.substringBefore('/')
        val repo = full.substringAfter('/', "")
        if (owner.isEmpty() || repo.isEmpty()) return DownloadStart.NotAvailable("This build has no repository information.")
        when (artifact.effectiveStatus(now())) {
            ArtifactStatus.EXPIRED -> return DownloadStart.NotAvailable("This artifact has expired on GitHub. Run the build again.")
            ArtifactStatus.DELETED -> return DownloadStart.NotAvailable("This artifact was deleted on GitHub. Run the build again.")
            ArtifactStatus.AVAILABLE -> Unit
        }
        val key = DownloadKey(record.id, artifact.id)
        val buildId = record.id
        synchronized(lock) {
            if (jobs[key]?.isActive == true) return DownloadStart.AlreadyRunning
            _states.update { it + (key to DownloadState.Active(DownloadProgress(DownloadProgress.Phase.CONNECTING))) }
            // LAZY: the job is registered before it can run, so even an instantly finishing download cleans up correctly.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val files = downloader.download(
                        owner = owner,
                        repo = repo,
                        artifactId = artifact.id,
                        destDir = store.dirFor(buildId),
                        tempDir = store.tempDir,
                        onProgress = { p -> _states.update { it + (key to DownloadState.Active(p)) } },
                    )
                    val stored = files.map { StoredApk(buildId, it) }
                    _states.update { it + (key to DownloadState.Finished(stored)) }
                } catch (e: CancellationException) {
                    _states.update { it + (key to DownloadState.Cancelled) }
                    throw e
                } catch (e: ArtifactDownloadException) {
                    when (e.kind) {
                        Kind.EXPIRED -> markArtifact(buildId, artifact.id, ArtifactStatus.EXPIRED)
                        Kind.NOT_FOUND -> markArtifact(buildId, artifact.id, ArtifactStatus.DELETED)
                        else -> Unit
                    }
                    _states.update { it + (key to DownloadState.Failed(e.kind, e.message.orEmpty(), e.retryable)) }
                } catch (e: Exception) {
                    _states.update { it + (key to DownloadState.Failed(Kind.UNKNOWN, "The download failed unexpectedly. Try again.", true)) }
                } finally {
                    synchronized(lock) { jobs.remove(key) }
                }
            }
            jobs[key] = job
            job.start()
        }
        return DownloadStart.Started
    }

    /** Stops a running download and removes its partial data. */
    fun cancel(key: DownloadKey) {
        val job = synchronized(lock) { jobs[key] }
        if (job != null && job.isActive) {
            _states.update { it + (key to DownloadState.Cancelled) }
            job.cancel()
        }
    }

    /** Hides a finished/failed/cancelled message. Active downloads are not affected. */
    fun dismiss(key: DownloadKey) {
        if (isActive(key)) return
        _states.update { it - key }
    }

    private suspend fun markArtifact(buildId: String, artifactId: Long, status: ArtifactStatus) {
        try {
            val record = builds.getBuild(buildId) ?: return
            val updated = record.artifacts.map { if (it.id == artifactId) it.copy(status = status) else it }
            if (updated == record.artifacts) return
            val availableNames = updated.filter { it.status == ArtifactStatus.AVAILABLE }.map { it.name }
            builds.saveBuild(record.copy(artifacts = updated, artifactNames = availableNames))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The history entry is only a cache of GitHub's state; failing to update it is not worth failing the download report.
        }
    }
}
