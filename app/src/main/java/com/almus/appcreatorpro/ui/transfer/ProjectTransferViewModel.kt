package com.almus.appcreatorpro.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.data.transfer.BackupStore
import com.almus.appcreatorpro.data.transfer.ProjectArchiver
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.transfer.ProjectNaming
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.service.transfer.DocumentAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether an export or backup is running (shown as a progress dialog on the Projects screen). */
sealed interface TransferOpState {
    data object Idle : TransferOpState
    data class Running(val title: String, val progress: TransferProgress?) : TransferOpState
}

/** Export a project as a ZIP to a place the user chooses, and make quick backups. */
class ProjectTransferViewModel(
    private val archiver: ProjectArchiver,
    private val documents: DocumentAccess,
    private val backups: BackupStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<TransferOpState>(TransferOpState.Idle)
    val state: StateFlow<TransferOpState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var pendingExport: Project? = null
    private var job: Job? = null

    /** Remembers which project is being exported and returns the suggested file name for the save dialog. */
    fun beginExport(project: Project): String {
        pendingExport = project
        return ProjectNaming.exportFileName(project.name, clock())
    }

    /** Called with the destination chosen in the system "save as" dialog, or null if the user cancelled. */
    fun completeExport(destinationUri: String?) {
        val project = pendingExport
        pendingExport = null
        if (destinationUri == null || project == null || _state.value is TransferOpState.Running) return
        val title = "Exporting \"${project.name}\""
        _state.value = TransferOpState.Running(title, null)
        job = viewModelScope.launch {
            try {
                val summary = withContext(ioDispatcher) {
                    documents.openOutput(destinationUri).use { out ->
                        archiver.writeZip(project.id, out) { p ->
                            val current = _state.value
                            if (current is TransferOpState.Running) _state.value = current.copy(progress = p)
                        }
                    }
                }
                val skippedNote = if (summary.skipped > 0) " (${summary.skipped} changed while exporting and were left out)" else ""
                _message.value = "Exported ${summary.fileCount} files as a ZIP$skippedNote."
            } catch (e: CancellationException) {
                removePartial(destinationUri)
                _message.value = "Export cancelled."
                throw e
            } catch (e: Exception) {
                removePartial(destinationUri)
                _message.value = e.toUserMessage()
            } finally {
                _state.value = TransferOpState.Idle
            }
        }
    }

    fun backupNow(project: Project) {
        if (_state.value is TransferOpState.Running) return
        _state.value = TransferOpState.Running("Backing up \"${project.name}\"", null)
        job = viewModelScope.launch {
            try {
                val info = backups.createBackup(project.id) { p ->
                    val current = _state.value
                    if (current is TransferOpState.Running) _state.value = current.copy(progress = p)
                }
                _message.value = "Backup saved (${info.fileCount} files)."
            } catch (e: CancellationException) {
                _message.value = "Backup cancelled."
                throw e
            } catch (e: Exception) {
                _message.value = e.toUserMessage()
            } finally {
                _state.value = TransferOpState.Idle
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun removePartial(uri: String) {
        withContext(NonCancellable + ioDispatcher) { documents.delete(uri) }
    }
}
