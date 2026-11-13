package com.almus.appcreatorpro.ui.artifacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.service.artifacts.ApkDownloadManager
import com.almus.appcreatorpro.service.artifacts.ArtifactCatalog
import com.almus.appcreatorpro.service.artifacts.ArtifactEntry
import com.almus.appcreatorpro.service.artifacts.ArtifactFilter
import com.almus.appcreatorpro.service.artifacts.ArtifactSort
import com.almus.appcreatorpro.service.artifacts.BuildContext
import com.almus.appcreatorpro.service.artifacts.BuildIntents
import com.almus.appcreatorpro.service.artifacts.CleanupPlan
import com.almus.appcreatorpro.service.artifacts.DownloadKey
import com.almus.appcreatorpro.service.artifacts.DownloadStart
import com.almus.appcreatorpro.service.artifacts.DownloadState
import com.almus.appcreatorpro.service.artifacts.ExportOutcome
import com.almus.appcreatorpro.service.artifacts.Checksums
import com.almus.appcreatorpro.ui.UiState
import com.almus.appcreatorpro.util.Formatters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/** A finished build whose APK/AAB is still on GitHub but not saved on this phone. */
data class PendingDownload(val record: BuildRecord, val artifact: BuildArtifactInfo)

/**
 * The artifact manager. Lists every APK/AAB saved on the phone, checks each one in the background (so the list appears at
 * once), and offers share, save, verify, delete (always confirmed by the screen) and clean-up of older files.
 *
 * Nothing here reads a keystore, a password or a private key: only the public facts found inside the files.
 */
class ArtifactsViewModel(
    private val builds: BuildStore,
    private val catalog: ArtifactCatalog,
    private val downloads: ApkDownloadManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<ArtifactEntry>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ArtifactEntry>>> = _state.asStateFlow()

    private val _filter = MutableStateFlow(ArtifactFilter())
    val filter: StateFlow<ArtifactFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(ArtifactSort.NEWEST)
    val sort: StateFlow<ArtifactSort> = _sort.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingDownload>>(emptyList())
    val pending: StateFlow<List<PendingDownload>> = _pending.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Progress of downloads started from this screen or elsewhere in the app. */
    val downloadStates: StateFlow<Map<DownloadKey, DownloadState>> =
        downloads?.states ?: MutableStateFlow<Map<DownloadKey, DownloadState>>(emptyMap()).asStateFlow()

    private var loadJob: Job? = null
    private var finishedSeen = 0

    init {
        load()
        downloads?.let { d ->
            viewModelScope.launch {
                d.states.collect { states ->
                    val finished = states.values.count { it is DownloadState.Finished }
                    if (finished > finishedSeen) load()
                    finishedSeen = finished
                }
            }
        }
    }

    /** Lists the saved files and checks each one. [recheck] forgets earlier results and looks at every file again. */
    fun load(recheck: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(ioDispatcher) {
                    if (recheck) catalog.forgetAll()
                    val all = builds.observeBuilds().first()
                    val contexts = HashMap<String, BuildContext>()
                    for (r in all) contexts[r.id] = BuildContext(r.projectName, BuildIntents.from(r.configuration))
                    val entries = catalog.list(contexts)
                    entries to pendingFrom(all, entries.map { it.buildId }.toSet())
                }
                _pending.value = loaded.second
                _state.value = UiState.Success(loaded.first)
                for (entry in loaded.first) {
                    if (entry.report != null) continue
                    val checked = withContext(ioDispatcher) { catalog.assess(entry) }
                    replace(checked)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error("The saved files could not be listed.")
            }
        }
    }

    private fun pendingFrom(all: List<BuildRecord>, buildIdsOnPhone: Set<String>): List<PendingDownload> {
        val now = System.currentTimeMillis()
        val out = ArrayList<PendingDownload>()
        for (r in all) {
            if (r.status != BuildStatus.SUCCESS || r.id in buildIdsOnPhone || r.repoFullName.isNullOrEmpty()) continue
            val artifact = r.artifacts.firstOrNull { it.isDeliverable && it.effectiveStatus(now) == ArtifactStatus.AVAILABLE } ?: continue
            out += PendingDownload(r, artifact)
        }
        return out.take(10)
    }

    private fun replace(updated: ArtifactEntry) {
        _state.update { s ->
            if (s is UiState.Success) UiState.Success(s.data.map { if (it.key == updated.key) updated else it }) else s
        }
    }

    fun setFilter(filter: ArtifactFilter) {
        _filter.value = filter
    }

    fun setSort(sort: ArtifactSort) {
        _sort.value = sort
    }

    /** Checks one file again from scratch: archive, signature, Android's verdict and checksum. */
    fun verify(entry: ArtifactEntry) {
        viewModelScope.launch {
            val checked = withContext(ioDispatcher) { catalog.assess(entry, force = true) }
            replace(checked)
            val r = checked.report
            _message.value = if (r == null) "The file could not be checked." else "${checked.fileName}: ${r.integrity.state.label}"
        }
    }

    /** The screen asks for confirmation before calling this. */
    fun delete(entry: ArtifactEntry) {
        viewModelScope.launch {
            val gone = withContext(ioDispatcher) { catalog.delete(entry) }
            if (gone) {
                _state.update { s -> if (s is UiState.Success) UiState.Success(s.data.filter { it.key != entry.key }) else s }
                _message.value = "Deleted ${entry.fileName}"
                refreshPending()
            } else {
                _message.value = "${entry.fileName} could not be deleted."
            }
        }
    }

    /** The screen shows [plan] and asks for confirmation before calling this. */
    fun cleanUp(plan: CleanupPlan) {
        viewModelScope.launch {
            val summary = withContext(ioDispatcher) { catalog.deleteMany(plan.delete) }
            val gone = plan.delete.map { it.key }.toSet()
            _state.update { s -> if (s is UiState.Success) UiState.Success(s.data.filter { it.key !in gone || fileStillThere(it) }) else s }
            _message.value = "Deleted ${summary.deleted} file(s), freed ${Formatters.formatBytes(summary.freedBytes)}" +
                if (summary.failed > 0) "; ${summary.failed} could not be deleted." else "."
            refreshPending()
        }
    }

    private fun fileStillThere(e: ArtifactEntry): Boolean = e.file.file.exists()

    private fun refreshPending() {
        viewModelScope.launch {
            val on = (state.value as? UiState.Success)?.data?.map { it.buildId }?.toSet().orEmpty()
            _pending.value = withContext(ioDispatcher) { pendingFrom(builds.observeBuilds().first(), on) }
        }
    }

    /** Copies the file into the stream the user chose (Storage Access Framework), and checks the copy's checksum. */
    fun export(entry: ArtifactEntry, open: () -> OutputStream?) {
        val blocked = entry.shareBlockedReason
        if (blocked != null) {
            _message.value = blocked
            return
        }
        val expected = entry.report?.integrity?.sha256
        viewModelScope.launch {
            _message.value = try {
                withContext(ioDispatcher) {
                    val out = open() ?: throw IOException("No destination was chosen.")
                    when (val r = out.use { catalog.export(entry, expected, it) }) {
                        is ExportOutcome.Saved -> "Saved ${entry.fileName} (SHA-256 ${Checksums.short(r.sha256)})"
                        is ExportOutcome.Changed -> "The file changed while it was being saved. Delete the saved copy and try again."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                "Could not save the file there: ${e.message ?: "unknown error"}"
            } catch (e: Exception) {
                "Could not save the file there."
            }
        }
    }

    /** Starts downloading a build's APK/AAB from GitHub. Progress appears in [downloadStates]. */
    fun download(item: PendingDownload) {
        val d = downloads ?: return
        when (val r = d.start(item.record, item.artifact)) {
            is DownloadStart.NotAvailable -> _message.value = r.reason
            DownloadStart.AlreadyRunning -> _message.value = "This file is already downloading."
            DownloadStart.Started -> Unit
        }
    }

    fun cancelDownload(item: PendingDownload) {
        downloads?.cancel(DownloadKey(item.record.id, item.artifact.id))
    }

    fun clearMessage() {
        _message.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }
}
