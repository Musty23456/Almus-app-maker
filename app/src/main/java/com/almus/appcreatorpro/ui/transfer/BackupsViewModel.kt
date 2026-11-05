package com.almus.appcreatorpro.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.data.transfer.BackupStore
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.transfer.BackupInfo
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupItem(val info: BackupInfo, val projectExists: Boolean)

data class BackupsUiState(
    val loading: Boolean = true,
    val items: List<BackupItem> = emptyList(),
    /** Name of the project when the screen is limited to one project. */
    val projectName: String? = null,
    val canCreate: Boolean = false,
    val busyTitle: String? = null,
    val progress: TransferProgress? = null,
    val message: String? = null,
    /** Set after "restore as new" so the screen can offer to open it. */
    val restored: Project? = null,
)

/** Lists, creates, restores and deletes backups. [projectId] null = backups of all projects. */
class BackupsViewModel(
    private val projectId: String?,
    private val backups: BackupStore,
    private val projects: ProjectRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupsUiState())
    val state: StateFlow<BackupsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val existing = projects.getProjects()
                val ids = existing.map { it.id }.toSet()
                val list = backups.listBackups(projectId).map { BackupItem(it, it.projectId in ids) }
                val current = projectId?.let { id -> existing.firstOrNull { it.id == id } }
                _state.update { it.copy(loading = false, items = list, projectName = current?.name, canCreate = current != null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = e.toUserMessage()) }
            }
        }
    }

    fun createBackup() {
        val id = projectId ?: return
        runBusy("Creating backup") {
            val info = backups.createBackup(id) { p -> _state.update { s -> s.copy(progress = p) } }
            "Backup saved (${info.fileCount} files)."
        }
    }

    /** Creates a separate copy of the project; nothing existing is changed. */
    fun restoreAsNew(item: BackupItem) {
        runBusy("Restoring backup") {
            val project = backups.restoreAsNew(item.info.id) { p -> _state.update { s -> s.copy(progress = p) } }
            _state.update { it.copy(restored = project) }
            "Restored as \"${project.name}\"."
        }
    }

    /** Replaces the original project's files. The UI must have asked for confirmation first. */
    fun restoreReplacing(item: BackupItem) {
        runBusy("Restoring backup") {
            val project = backups.restoreReplacing(item.info.id) { p -> _state.update { s -> s.copy(progress = p) } }
            "\"${project.name}\" was restored. A safety copy of its previous state was saved as a backup."
        }
    }

    fun delete(item: BackupItem) {
        runBusy("Deleting backup") {
            backups.deleteBackup(item.info.id)
            "Backup deleted."
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun consumeRestored() = _state.update { it.copy(restored = null) }

    private fun runBusy(title: String, block: suspend () -> String) {
        if (_state.value.busyTitle != null) return
        _state.update { it.copy(busyTitle = title, progress = null) }
        viewModelScope.launch {
            var message: String? = null
            try {
                message = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.toUserMessage()
            } finally {
                _state.update { it.copy(busyTitle = null, progress = null, message = message ?: it.message) }
            }
            refresh()
        }
    }
}
