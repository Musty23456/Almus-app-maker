package com.almus.appcreatorpro.ui.builds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.repository.BuildRepository
import com.almus.appcreatorpro.service.artifacts.ApkDownloadManager
import com.almus.appcreatorpro.service.artifacts.ApkStore
import com.almus.appcreatorpro.service.artifacts.DownloadKey
import com.almus.appcreatorpro.service.artifacts.DownloadStart
import com.almus.appcreatorpro.service.artifacts.DownloadState
import com.almus.appcreatorpro.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the history card says about a build's APK. */
enum class ApkAvailability {
    /** Saved on this phone. */
    ON_PHONE,

    /** Listed on GitHub and not expired: can be downloaded. */
    DOWNLOADABLE,
    EXPIRED,
    DELETED,

    /** Build did not produce an APK (failed, cancelled, still running, or no artifact listed). */
    NONE,
}

class BuildHistoryViewModel(
    private val buildRepository: BuildRepository,
    private val downloads: ApkDownloadManager? = null,
    private val apkStore: ApkStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<BuildRecord>>>(UiState.Loading)
    val state: StateFlow<UiState<List<BuildRecord>>> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** One-line feedback for the user (for example why a download could not start). Cleared with [clearMessage]. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _savedBuildIds = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of builds that have at least one APK saved on the phone. */
    val savedBuildIds: StateFlow<Set<String>> = _savedBuildIds.asStateFlow()

    /** Live download states, keyed by build and artifact. */
    val downloadStates: StateFlow<Map<DownloadKey, DownloadState>> = downloads?.states ?: MutableStateFlow(emptyMap())

    init {
        load()
        downloads?.let { manager ->
            viewModelScope.launch {
                manager.states.collect { states ->
                    if (states.values.any { it is DownloadState.Finished }) refreshSaved()
                }
            }
        }
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load() {
        _state.value = UiState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                if (buildRepository is com.almus.appcreatorpro.domain.repository.BuildStore) {
                    // Real history: stays live while builds change status.
                    buildRepository.observeBuilds().collect {
                        _state.value = UiState.Success(it)
                        refreshSaved(it)
                    }
                    return@launch
                }
                _state.value = UiState.Success(buildRepository.getBuildHistory())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.toUserMessage())
            }
        }
    }

    private fun refreshSaved(records: List<BuildRecord>? = (_state.value as? UiState.Success)?.data) {
        val store = apkStore ?: return
        val list = records ?: return
        _savedBuildIds.value = list.filter { !it.isSample && it.id.matches(SAFE_ID) && store.list(it.id).isNotEmpty() }.map { it.id }.toSet()
    }

    /** Which downloadable artifact (if any) the history card should offer for [record]. */
    fun downloadableArtifact(record: BuildRecord): BuildArtifactInfo? =
        record.artifacts.firstOrNull { it.isApk && it.effectiveStatus(now()) == ArtifactStatus.AVAILABLE }

    fun availability(record: BuildRecord): ApkAvailability {
        if (record.id in _savedBuildIds.value) return ApkAvailability.ON_PHONE
        val apks = record.artifacts.filter { it.isApk }
        return when {
            apks.any { it.effectiveStatus(now()) == ArtifactStatus.AVAILABLE } -> ApkAvailability.DOWNLOADABLE
            apks.any { it.effectiveStatus(now()) == ArtifactStatus.EXPIRED } -> ApkAvailability.EXPIRED
            apks.any { it.effectiveStatus(now()) == ArtifactStatus.DELETED } -> ApkAvailability.DELETED
            else -> ApkAvailability.NONE
        }
    }

    fun download(record: BuildRecord) {
        val manager = downloads ?: return
        val artifact = downloadableArtifact(record)
        if (artifact == null) {
            _message.value = when (availability(record)) {
                ApkAvailability.EXPIRED -> "The APK of this build has expired on GitHub. Build again to get a new one."
                ApkAvailability.DELETED -> "The APK of this build was deleted on GitHub. Build again to get a new one."
                else -> "This build has no APK to download."
            }
            return
        }
        when (val r = manager.start(record, artifact)) {
            DownloadStart.Started, DownloadStart.AlreadyRunning -> Unit
            is DownloadStart.NotAvailable -> _message.value = r.reason
        }
    }

    fun cancelDownload(record: BuildRecord) {
        val artifact = record.artifacts.firstOrNull { it.isApk } ?: return
        downloads?.cancel(DownloadKey(record.id, artifact.id))
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
