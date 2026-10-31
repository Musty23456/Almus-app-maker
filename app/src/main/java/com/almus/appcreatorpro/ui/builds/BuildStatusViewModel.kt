package com.almus.appcreatorpro.ui.builds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.service.artifacts.ApkDownloadManager
import com.almus.appcreatorpro.service.artifacts.ApkStore
import com.almus.appcreatorpro.service.artifacts.DownloadKey
import com.almus.appcreatorpro.service.artifacts.DownloadStart
import com.almus.appcreatorpro.service.artifacts.DownloadState
import com.almus.appcreatorpro.service.artifacts.StoredApk
import com.almus.appcreatorpro.service.build.BuildDetails
import com.almus.appcreatorpro.service.build.BuildDetailsService
import com.almus.appcreatorpro.service.build.BuildLogView
import com.almus.appcreatorpro.service.build.BuildService
import com.almus.appcreatorpro.service.build.BuildTracker
import com.almus.appcreatorpro.service.build.DetailsResult
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.GitHubAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream

sealed interface BuildStatusState {
    data object Loading : BuildStatusState
    data object NotFound : BuildStatusState
    data class Found(val record: BuildRecord, val polling: Boolean) : BuildStatusState
}

/** Steps, logs and artifacts loaded from GitHub on demand. */
sealed interface DetailsUi {
    data object Idle : DetailsUi
    data object Loading : DetailsUi
    data class Loaded(val details: BuildDetails) : DetailsUi
    data class Error(val message: String, val retryable: Boolean) : DetailsUi
}

sealed interface LogUi {
    data object Hidden : LogUi
    data object Loading : LogUi
    data class Shown(val view: BuildLogView) : LogUi
}

sealed interface RetryUi {
    data object Idle : RetryUi
    data object Starting : RetryUi

    /** The new build's id, so the screen can open it. */
    data class Started(val newBuildId: String) : RetryUi
    data class Failed(val message: String) : RetryUi
}

/** One-shot result of copying an APK to a place the user chose. */
sealed interface ExportUi {
    data object Idle : ExportUi
    data class Done(val fileName: String) : ExportUi
    data class Failed(val message: String) : ExportUi
}

/**
 * Live view of one build: status (updated by the tracker), details/logs/artifacts (loaded from GitHub),
 * APK downloads (run by [ApkDownloadManager]), retry and sign-in state. The Phase 3 constructor still works;
 * the Phase 4 services are optional so older callers and tests are unaffected.
 */
class BuildStatusViewModel(
    private val buildId: String,
    private val builds: BuildStore,
    private val tracker: BuildTracker,
    private val detailsService: BuildDetailsService? = null,
    private val downloads: ApkDownloadManager? = null,
    private val apkStore: ApkStore? = null,
    private val buildService: BuildService? = null,
    private val auth: GitHubAuthManager? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val state: StateFlow<BuildStatusState> =
        combine(builds.observeBuilds(), tracker.activeBuildIds) { all, active ->
            val record = all.firstOrNull { it.id == buildId }
            if (record == null) BuildStatusState.NotFound else BuildStatusState.Found(record, buildId in active)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, BuildStatusState.Loading)

    private val _details = MutableStateFlow<DetailsUi>(DetailsUi.Idle)
    val details: StateFlow<DetailsUi> = _details.asStateFlow()

    private val _log = MutableStateFlow<LogUi>(LogUi.Hidden)
    val log: StateFlow<LogUi> = _log.asStateFlow()

    private val _retry = MutableStateFlow<RetryUi>(RetryUi.Idle)
    val retry: StateFlow<RetryUi> = _retry.asStateFlow()

    private val _export = MutableStateFlow<ExportUi>(ExportUi.Idle)
    val export: StateFlow<ExportUi> = _export.asStateFlow()

    private val _savedApks = MutableStateFlow<List<StoredApk>>(emptyList())

    /** APKs of this build that are saved on the phone. */
    val savedApks: StateFlow<List<StoredApk>> = _savedApks.asStateFlow()

    /** Download progress of this build's artifacts. */
    val downloadStates: StateFlow<Map<DownloadKey, DownloadState>> =
        (downloads?.states ?: MutableStateFlow(emptyMap())).let { flow ->
            flow.map { all -> all.filterKeys { it.buildId == buildId } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
        }

    /** True when the GitHub sign-in is gone, so status checks and downloads cannot work until the user reconnects. */
    val needsReconnect: StateFlow<Boolean> =
        (auth?.state ?: MutableStateFlow<GitHubAuthState>(GitHubAuthState.Disconnected)).let { flow ->
            flow.map { it is GitHubAuthState.Expired || it is GitHubAuthState.Disconnected }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)
        }

    private var detailsJob: Job? = null
    private var lastDetailsStatus: Any? = null

    init {
        refresh()
        refreshSavedApks()
        // Reload steps/artifacts whenever the status changes (queued -> running -> finished), without polling GitHub more often.
        viewModelScope.launch {
            state.map { (it as? BuildStatusState.Found)?.record?.status }.distinctUntilChanged().collect { status ->
                if (status != null && lastDetailsStatus != status) {
                    lastDetailsStatus = status
                    val record = (state.value as? BuildStatusState.Found)?.record
                    if (record?.runId != null) loadDetails()
                }
            }
        }
        // A finished download changes what is saved on the phone.
        viewModelScope.launch {
            downloadStates.collect { states ->
                if (states.values.any { it is DownloadState.Finished }) refreshSavedApks()
            }
        }
    }

    /** Checks GitHub again (and resumes polling if it had stopped), and reloads the details. Finished builds are not polled. */
    fun refresh() {
        viewModelScope.launch {
            val record = builds.getBuild(buildId)
            if (record != null && !record.status.isTerminal) tracker.track(buildId)
        }
        if ((state.value as? BuildStatusState.Found)?.record?.runId != null) loadDetails()
    }

    fun loadDetails() {
        val service = detailsService ?: return
        detailsJob?.cancel()
        if (_details.value !is DetailsUi.Loaded) _details.value = DetailsUi.Loading
        detailsJob = viewModelScope.launch {
            try {
                val record = (state.value as? BuildStatusState.Found)?.record ?: builds.getBuild(buildId)
                val includeLog = record?.status?.let { it == com.almus.appcreatorpro.domain.model.BuildStatus.FAILED || it == com.almus.appcreatorpro.domain.model.BuildStatus.CANCELLED } == true
                _details.value = when (val result = service.load(buildId, includeLog)) {
                    is DetailsResult.Loaded -> {
                        if (result.details.log != null) _log.value = LogUi.Shown(result.details.log)
                        DetailsUi.Loaded(result.details)
                    }
                    is DetailsResult.Failed -> DetailsUi.Error(result.message, result.retryable)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _details.value = DetailsUi.Error("Could not load the build details. Try again.", true)
            }
        }
    }

    /** Shows the log of this build (also for successful builds). */
    fun loadLog() {
        val service = detailsService ?: return
        if (_log.value is LogUi.Loading) return
        _log.value = LogUi.Loading
        viewModelScope.launch {
            _log.value = try {
                LogUi.Shown(service.loadLog(buildId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUi.Shown(BuildLogView(null, emptyList(), "The log could not be loaded. Try again."))
            }
        }
    }

    // ------------------------------------------------------------------ downloads

    /** Starts downloading [artifact]. Returns a message if it cannot start (null = started or already running). */
    fun download(artifact: BuildArtifactInfo): String? {
        val manager = downloads ?: return "Downloads are not available."
        val record = (state.value as? BuildStatusState.Found)?.record ?: return "This build is not in the history."
        return when (val result = manager.start(record, artifact)) {
            DownloadStart.Started, DownloadStart.AlreadyRunning -> null
            is DownloadStart.NotAvailable -> result.reason
        }
    }

    fun cancelDownload(artifact: BuildArtifactInfo) {
        downloads?.cancel(DownloadKey(buildId, artifact.id))
    }

    fun dismissDownload(artifact: BuildArtifactInfo) {
        downloads?.dismiss(DownloadKey(buildId, artifact.id))
    }

    fun refreshSavedApks() {
        val store = apkStore ?: return
        viewModelScope.launch {
            _savedApks.value = try {
                store.list(buildId)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun deleteApk(apk: StoredApk) {
        val store = apkStore ?: return
        store.delete(apk.buildId, apk.fileName)
        refreshSavedApks()
    }

    /** Copies [apk] into the stream the user chose (Storage Access Framework). [open] returns the stream or null. */
    fun exportApk(apk: StoredApk, open: () -> OutputStream?) {
        val store = apkStore ?: return
        viewModelScope.launch {
            _export.value = try {
                val out = open() ?: throw IOException("No destination was chosen.")
                out.use { store.copyTo(apk, it) }
                ExportUi.Done(apk.fileName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ExportUi.Failed("Could not save the APK there: ${e.message ?: "unknown error"}")
            } catch (e: Exception) {
                ExportUi.Failed("Could not save the APK there.")
            }
        }
    }

    fun clearExport() {
        _export.update { ExportUi.Idle }
    }

    // ------------------------------------------------------------------ retry

    /** Builds the project again with its current files. Ignored while a retry is already starting (no duplicates). */
    fun retryBuild() {
        val service = buildService ?: return
        if (_retry.value is RetryUi.Starting) return
        val record = (state.value as? BuildStatusState.Found)?.record ?: return
        _retry.value = RetryUi.Starting
        viewModelScope.launch {
            _retry.value = try {
                service.retryBuild(record).fold(
                    onSuccess = { RetryUi.Started(it.id) },
                    onFailure = { RetryUi.Failed(it.message ?: "The build could not be started.") },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RetryUi.Failed("The build could not be started.")
            }
        }
    }

    fun clearRetry() {
        _retry.value = RetryUi.Idle
    }

    fun nowMillis(): Long = now()
}
