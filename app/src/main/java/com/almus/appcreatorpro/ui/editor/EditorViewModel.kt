package com.almus.appcreatorpro.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.debug.SourceText
import com.almus.appcreatorpro.domain.error.StorageException
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.SourceFileEntry
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import com.almus.appcreatorpro.domain.template.ProjectTemplates
import com.almus.appcreatorpro.editor.CodeEditing
import com.almus.appcreatorpro.editor.EditorValue
import com.almus.appcreatorpro.editor.FindEngine
import com.almus.appcreatorpro.editor.Language
import com.almus.appcreatorpro.editor.UndoHistory
import com.almus.appcreatorpro.explorer.DeletePlan
import com.almus.appcreatorpro.explorer.ExplorerOperations
import com.almus.appcreatorpro.explorer.ExplorerSort
import com.almus.appcreatorpro.explorer.FilePreview
import com.almus.appcreatorpro.explorer.FilePreviewBuilder
import com.almus.appcreatorpro.explorer.FileTypes
import com.almus.appcreatorpro.explorer.InMemoryRecentFilesStore
import com.almus.appcreatorpro.explorer.OpResult
import com.almus.appcreatorpro.explorer.PathOps
import com.almus.appcreatorpro.explorer.ProtectedFiles
import com.almus.appcreatorpro.explorer.RecentFiles
import com.almus.appcreatorpro.explorer.RecentFilesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One file open in a tab. [savedText] is what is on disk; the file is dirty when it differs from the buffer. */
data class OpenFile(
    val path: String,
    val value: EditorValue,
    val savedText: String,
    /** Bumped whenever the ViewModel (not the user's typing) changes [value], so the UI can reload it. */
    val revision: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val isDirty: Boolean get() = value.text != savedText
    val name: String get() = path.substringAfterLast('/')
    val language: Language get() = Language.fromFileName(path)
}

data class FindState(
    val visible: Boolean = false,
    val query: String = "",
    val replacement: String = "",
    val caseSensitive: Boolean = false,
    val matchCount: Int = 0,
    /** Index of the currently selected match, or -1. */
    val currentIndex: Int = -1,
)

data class EditorMessage(val id: Long, val text: String, val isError: Boolean)

/** Phase 25: the preview sheet. Exactly one of [preview] / [error] is set once [loading] is false. */
data class PreviewUi(
    val path: String,
    val loading: Boolean = true,
    val preview: FilePreview? = null,
    val error: String? = null,
)

/** Phase 25: a rename/move of important file(s) waiting for the user's OK. Nothing has been changed yet. */
data class PendingProtectedRename(val from: String, val to: String, val isFolder: Boolean, val message: String)

data class EditorUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val project: Project? = null,
    val files: List<SourceFileEntry> = emptyList(),
    val openFiles: List<OpenFile> = emptyList(),
    val activePath: String? = null,
    val collapsedFolders: Set<String> = emptySet(),
    val savingPaths: Set<String> = emptySet(),
    val find: FindState = FindState(),
    // ---- Phase 25: project explorer
    val searchQuery: String = "",
    val sort: ExplorerSort = ExplorerSort.NAME,
    /** Most recently opened files of this project, newest first. */
    val recentFiles: List<String> = emptyList(),
    val preview: PreviewUi? = null,
    /** A delete waiting for confirmation (typed confirmation when important files are inside). */
    val pendingDelete: DeletePlan? = null,
    val pendingProtectedRename: PendingProtectedRename? = null,
) {
    val activeFile: OpenFile? get() = openFiles.firstOrNull { it.path == activePath }
    val hasUnsavedChanges: Boolean get() = openFiles.any { it.isDirty }
    val isSaving: Boolean get() = savingPaths.isNotEmpty()
}

/**
 * Holds everything about one project being edited: the file list, open tabs, text buffers,
 * undo history, find/replace and saving. All text logic lives in the pure `editor` package.
 */
class EditorViewModel(
    private val projectId: String,
    private val projects: ProjectRepository,
    private val files: SourceFileRepository,
    /** Phase 25: remembers recently opened files. Optional so older callers compile unchanged. */
    private val recentStore: RecentFilesStore = InMemoryRecentFilesStore(),
) : ViewModel() {

    private val ops = ExplorerOperations(projectId, files)
    private var recent = RecentFiles()

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<EditorMessage?>(null)
    val message: StateFlow<EditorMessage?> = _message.asStateFlow()

    private val histories = HashMap<String, UndoHistory>()
    private val saveMutex = Mutex()
    private var messageCounter = 0L

    init {
        load()
    }

    // ------------------------------------------------------------------ loading

    fun load() {
        _state.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val project = projects.getProject(projectId)
                    ?: throw StorageException(StorageException.Kind.NOT_FOUND, "This project no longer exists.")
                val list = files.listFiles(projectId)
                recent = recentStore.load(projectId).pruned(list.map { it.path }.toSet())
                _state.update { it.copy(isLoading = false, project = project, files = list, recentFiles = recent.paths) }
                if (_state.value.openFiles.isEmpty()) {
                    val wanted = ProjectTemplates.defaultOpenFile(project.type, project.packageName)
                    val first = list.firstOrNull { it.path == wanted }?.path ?: list.firstOrNull()?.path
                    if (first != null) openFileInternal(first)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, loadError = e.toUserMessage()) }
            }
        }
    }

    // ------------------------------------------------------------------ tabs

    fun openFile(path: String) {
        viewModelScope.launch { openFileInternal(path) }
    }

    private suspend fun openFileInternal(path: String) {
        if (_state.value.openFiles.any { it.path == path }) {
            _state.update { it.copy(activePath = path) }
            touchRecent(path)
            return
        }
        try {
            val text = files.readFile(projectId, path)
            histories[path] = UndoHistory()
            _state.update {
                if (it.openFiles.any { f -> f.path == path }) {
                    it.copy(activePath = path)
                } else {
                    it.copy(
                        openFiles = it.openFiles + OpenFile(path, EditorValue(text, 0, 0), text),
                        activePath = path,
                    )
                }
            }
            touchRecent(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            post(e.toUserMessage(), isError = true)
        }
    }

    /**
     * Phase 8: opens [path] and puts the caret on [line] (and [column] when given; otherwise the whole line is
     * selected). A line past the end of the file is clamped, so a stale line number never crashes the editor.
     */
    fun openAt(path: String, line: Int?, column: Int?) {
        viewModelScope.launch {
            openFileInternal(path)
            val open = _state.value.openFiles.firstOrNull { it.path == path } ?: return@launch
            val sel = SourceText.selectionFor(open.value.text, line, column)
            applyProgrammatic(path, open.value.copy(selStart = sel.first, selEnd = sel.last), recordHistory = false)
        }
    }

    /**
     * Phase 8: the debug screen can change files on disk (applied fixes, undo). When the editor comes back to the
     * front, tabs without unsaved typing are re-read; a tab with unsaved typing is never overwritten.
     */
    fun resyncFromDisk() {
        viewModelScope.launch {
            var conflicts = 0
            for (open in _state.value.openFiles) {
                val disk = try { files.readFile(projectId, open.path) } catch (e: CancellationException) { throw e } catch (e: Exception) { continue }
                if (disk == open.savedText) continue
                if (open.isDirty) { conflicts++; continue }
                val caret = open.value.cursor.coerceAtMost(disk.length)
                histories[open.path] = UndoHistory()
                replaceOpen(open.path) {
                    it.copy(value = EditorValue(disk, caret, caret), savedText = disk, revision = it.revision + 1, canUndo = false, canRedo = false)
                }
            }
            if (conflicts > 0) post("$conflicts open file(s) changed on disk but have unsaved edits here; they were left as they are.", isError = true)
        }
    }

    fun selectTab(path: String) {
        _state.update { if (it.openFiles.any { f -> f.path == path }) it.copy(activePath = path) else it }
        refreshFind()
    }

    /** Closes a tab. The UI must confirm first when [OpenFile.isDirty]; unsaved text is discarded here. */
    fun closeFile(path: String) {
        _state.update { s ->
            val index = s.openFiles.indexOfFirst { it.path == path }
            if (index < 0) return@update s
            val remaining = s.openFiles.filterNot { it.path == path }
            val newActive = if (s.activePath == path) remaining.getOrNull(minOf(index, remaining.lastIndex))?.path else s.activePath
            s.copy(openFiles = remaining, activePath = newActive)
        }
        histories.remove(path)
        refreshFind()
    }

    fun toggleFolder(path: String) {
        _state.update {
            it.copy(collapsedFolders = if (path in it.collapsedFolders) it.collapsedFolders - path else it.collapsedFolders + path)
        }
    }

    // ------------------------------------------------------------------ editing

    /**
     * Called synchronously for every change the user types. Returns the value the text field
     * should display (auto-indent may adjust what was typed).
     */
    fun onUserEdit(new: EditorValue): EditorValue {
        val path = _state.value.activePath ?: return new
        val open = _state.value.openFiles.firstOrNull { it.path == path } ?: return new
        val old = open.value
        val adjusted = (if (new.text != old.text) CodeEditing.afterInput(old, new) else new).coerced()
        val history = history(path)
        if (adjusted.text != old.text) history.record(old, adjusted)
        replaceOpen(path) {
            it.copy(
                value = adjusted,
                revision = if (adjusted == new.coerced()) it.revision else it.revision + 1,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
            )
        }
        if (adjusted.text != old.text) refreshFind()
        return adjusted
    }

    fun undo() {
        val open = _state.value.activeFile ?: return
        val restored = history(open.path).undo(open.value) ?: return
        applyProgrammatic(open.path, restored, recordHistory = false)
    }

    fun redo() {
        val open = _state.value.activeFile ?: return
        val restored = history(open.path).redo(open.value) ?: return
        applyProgrammatic(open.path, restored, recordHistory = false)
    }

    fun indent() = edit { CodeEditing.indent(it) }

    fun outdent() = edit { CodeEditing.outdent(it) }

    /** Inserts text at the caret, replacing any selection (used by the symbol bar and paste). */
    fun insertText(text: String) = edit { it.replaceSelection(text) }

    fun selectAll() {
        val open = _state.value.activeFile ?: return
        applyProgrammatic(open.path, open.value.copy(selStart = 0, selEnd = open.value.text.length), recordHistory = false)
    }

    private fun edit(transform: (EditorValue) -> EditorValue) {
        val open = _state.value.activeFile ?: return
        applyProgrammatic(open.path, transform(open.value), recordHistory = true)
    }

    private fun applyProgrammatic(path: String, newValue: EditorValue, recordHistory: Boolean) {
        val open = _state.value.openFiles.firstOrNull { it.path == path } ?: return
        val value = newValue.coerced()
        val history = history(path)
        if (recordHistory && value.text != open.value.text) history.record(open.value, value)
        replaceOpen(path) {
            it.copy(value = value, revision = it.revision + 1, canUndo = history.canUndo, canRedo = history.canRedo)
        }
        if (value.text != open.value.text) refreshFind()
    }

    // ------------------------------------------------------------------ saving

    fun saveActive() {
        val path = _state.value.activePath ?: return
        viewModelScope.launch {
            val wasDirty = _state.value.openFiles.firstOrNull { it.path == path }?.isDirty == true
            if (saveInternal(path) && wasDirty) post("Saved ${path.substringAfterLast('/')}", isError = false)
        }
    }

    /** Saves one specific file; [onDone] receives true if it is saved afterwards. */
    fun saveFile(path: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onDone(saveInternal(path)) }
    }

    /** Saves every modified file. [onDone] receives true if nothing is left unsaved. */
    fun saveAll(silent: Boolean = true, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val dirty = _state.value.openFiles.filter { it.isDirty }.map { it.path }
            var ok = true
            for (path in dirty) if (!saveInternal(path)) ok = false
            if (ok && dirty.isNotEmpty() && !silent) post("Saved ${dirty.size} file(s).", isError = false)
            onDone(ok)
        }
    }

    private suspend fun saveInternal(path: String): Boolean = saveMutex.withLock {
        val open = _state.value.openFiles.firstOrNull { it.path == path } ?: return@withLock true
        if (!open.isDirty) return@withLock true
        val text = open.value.text
        _state.update { it.copy(savingPaths = it.savingPaths + path) }
        try {
            files.writeFile(projectId, path, text)
            replaceOpen(path) { it.copy(savedText = text) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            post("Could not save ${path.substringAfterLast('/')}: ${e.toUserMessage()}", isError = true)
            false
        } finally {
            _state.update { it.copy(savingPaths = it.savingPaths - path) }
        }
    }

    // ------------------------------------------------------------------ file operations

    fun createFile(path: String) {
        viewModelScope.launch {
            when (val r = ops.createFile(path)) {
                is OpResult.Done -> {
                    refreshFiles()
                    r.created?.let { openFileInternal(it) }
                    post(r.message, isError = false)
                }
                is OpResult.Failed -> post(r.message, isError = true)
                is OpResult.NeedsConfirmation -> post(r.message, isError = true)
            }
        }
    }

    /** Phase 25: a new folder (kept alive by an empty .gitkeep file, because folders only exist while they hold a file). */
    fun createFolder(path: String) {
        viewModelScope.launch {
            when (val r = ops.createFolder(path)) {
                is OpResult.Done -> {
                    refreshFiles()
                    post(r.message, isError = false)
                }
                is OpResult.Failed -> post(r.message, isError = true)
                is OpResult.NeedsConfirmation -> post(r.message, isError = true)
            }
        }
    }

    /**
     * Renames or moves a file. An open (even modified) buffer follows the file to its new path. Important
     * configuration files are not touched until the user confirms ([confirmProtectedRename]).
     */
    fun renameFile(from: String, to: String) {
        viewModelScope.launch { runRename(from, to, isFolder = false, confirmed = false) }
    }

    /** Phase 25: renames or moves a whole folder (open tabs inside it follow). */
    fun renameFolder(from: String, to: String) {
        viewModelScope.launch { runRename(from, to, isFolder = true, confirmed = false) }
    }

    fun confirmProtectedRename() {
        val pending = _state.value.pendingProtectedRename ?: return
        _state.update { it.copy(pendingProtectedRename = null) }
        viewModelScope.launch { runRename(pending.from, pending.to, pending.isFolder, confirmed = true) }
    }

    fun cancelProtectedRename() {
        _state.update { it.copy(pendingProtectedRename = null) }
    }

    private suspend fun runRename(from: String, to: String, isFolder: Boolean, confirmed: Boolean) {
        val r = if (isFolder) ops.renameFolder(from, to, confirmed) else ops.rename(from, to, confirmed)
        when (r) {
            is OpResult.Done -> {
                applyMoves(r.moved, folderFrom = if (isFolder) PathOps.normalizeInput(from).trimEnd('/') else null,
                    folderTo = if (isFolder) PathOps.normalizeInput(to).trimEnd('/') else null)
                refreshFiles()
                post(r.message, isError = false)
            }
            is OpResult.Failed -> post(r.message, isError = true)
            is OpResult.NeedsConfirmation ->
                _state.update { it.copy(pendingProtectedRename = PendingProtectedRename(from, to, isFolder, r.message)) }
        }
    }

    /** Open tabs, undo histories and the recent list follow files that moved. */
    private fun applyMoves(moved: Map<String, String>, folderFrom: String?, folderTo: String?) {
        if (moved.isEmpty()) return
        _state.update { s ->
            s.copy(
                openFiles = s.openFiles.map { f -> moved[f.path]?.let { f.copy(path = it) } ?: f },
                activePath = s.activePath?.let { moved[it] ?: it },
            )
        }
        for ((old, new) in moved) histories.remove(old)?.let { histories[new] = it }
        recent = if (folderFrom != null && folderTo != null) recent.renamedUnder(folderFrom, folderTo)
        else moved.entries.fold(recent) { acc, e -> acc.renamed(e.key, e.value) }
        saveRecent()
    }

    /**
     * Deletes one file. Important configuration files are refused here: they only go through
     * [requestDelete] / [confirmDelete], which makes the user type the file name.
     */
    fun deleteFile(path: String) {
        if (ProtectedFiles.isProtected(path)) {
            post("\"${path.substringAfterLast('/')}\" is an important file. Use Delete in the explorer and type its name to confirm.", isError = true)
            return
        }
        viewModelScope.launch {
            val plan = ops.planDelete(path, isFolder = false)
            if (plan == null) {
                post("'$path' no longer exists.", isError = true)
                return@launch
            }
            finishDelete(ops.delete(plan, null))
        }
    }

    /** Phase 25: starts a delete. The UI shows [EditorUiState.pendingDelete] and calls [confirmDelete] or [cancelDelete]. */
    fun requestDelete(path: String, isFolder: Boolean) {
        viewModelScope.launch {
            try {
                val plan = ops.planDelete(path, isFolder)
                if (plan == null) {
                    post("'$path' no longer exists.", isError = true)
                    return@launch
                }
                val dirty = _state.value.openFiles.filter { it.isDirty && it.path in plan.files }.map { it.path }
                _state.update { it.copy(pendingDelete = plan.copy(unsaved = dirty)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(e.toUserMessage(), isError = true)
            }
        }
    }

    fun confirmDelete(typedConfirmation: String? = null) {
        val plan = _state.value.pendingDelete ?: return
        viewModelScope.launch {
            val r = ops.delete(plan, typedConfirmation)
            // A wrong typed confirmation keeps the dialog open so the user can try again.
            if (r is OpResult.Failed && plan.needsTypedConfirmation && typedConfirmation?.trim() != plan.confirmationWord) {
                post(r.message, isError = true)
                return@launch
            }
            _state.update { it.copy(pendingDelete = null) }
            finishDelete(r)
        }
    }

    fun cancelDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    private suspend fun finishDelete(r: OpResult) {
        when (r) {
            is OpResult.Done -> {
                for (p in r.deleted) closeFile(p)
                recent = r.deleted.fold(recent) { acc, p -> acc.removed(p) }
                saveRecent()
                refreshFiles()
                post(r.message, isError = false)
            }
            is OpResult.Failed -> post(r.message, isError = true)
            is OpResult.NeedsConfirmation -> post(r.message, isError = true)
        }
    }

    /** Phase 25: copies a file next to the original as "Name copy.ext" (never overwrites) and opens the copy. */
    fun duplicateFile(path: String) {
        viewModelScope.launch {
            when (val r = ops.duplicate(path)) {
                is OpResult.Done -> {
                    refreshFiles()
                    r.created?.let { openFileInternal(it) }
                    post(r.message, isError = false)
                }
                is OpResult.Failed -> post(r.message, isError = true)
                is OpResult.NeedsConfirmation -> post(r.message, isError = true)
            }
        }
    }

    fun renameProject(newName: String) {
        viewModelScope.launch {
            try {
                val updated = projects.renameProject(projectId, newName)
                _state.update { it.copy(project = updated) }
                post("Project renamed to \"${updated.name}\".", isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(e.toUserMessage(), isError = true)
            }
        }
    }

    private suspend fun refreshFiles() {
        val list = files.listFiles(projectId)
        _state.update { it.copy(files = list) }
        val pruned = recent.pruned(list.map { it.path }.toSet())
        if (pruned != recent) {
            recent = pruned
            saveRecent()
        }
    }

    // ------------------------------------------------------------------ find & replace

    fun toggleFind() {
        _state.update { it.copy(find = it.find.copy(visible = !it.find.visible)) }
        refreshFind()
    }

    fun setFindQuery(query: String) {
        _state.update { it.copy(find = it.find.copy(query = query, currentIndex = -1)) }
        refreshFind()
    }

    fun setReplacement(text: String) {
        _state.update { it.copy(find = it.find.copy(replacement = text)) }
    }

    fun setCaseSensitive(enabled: Boolean) {
        _state.update { it.copy(find = it.find.copy(caseSensitive = enabled, currentIndex = -1)) }
        refreshFind()
    }

    fun findNext() = navigateFind(forward = true)

    fun findPrevious() = navigateFind(forward = false)

    fun replaceCurrent() {
        val open = _state.value.activeFile ?: return
        val find = _state.value.find
        if (find.query.isEmpty()) return
        val selected = open.value.selectedText
        if (selected.isNotEmpty() && selected.equals(find.query, ignoreCase = !find.caseSensitive)) {
            applyProgrammatic(open.path, open.value.replaceSelection(find.replacement), recordHistory = true)
        }
        navigateFind(forward = true)
    }

    fun replaceAll() {
        val open = _state.value.activeFile ?: return
        val find = _state.value.find
        if (find.query.isEmpty()) return
        val (newText, count) = FindEngine.replaceAll(open.value.text, find.query, find.replacement, find.caseSensitive)
        if (count == 0) {
            post("No matches for \"${find.query}\".", isError = false)
            return
        }
        val caret = minOf(open.value.cursor, newText.length)
        applyProgrammatic(open.path, EditorValue(newText, caret, caret), recordHistory = true)
        post("Replaced $count occurrence(s).", isError = false)
    }

    private fun navigateFind(forward: Boolean) {
        val open = _state.value.activeFile ?: return
        val find = _state.value.find
        if (find.query.isEmpty()) return
        val matches = FindEngine.findAll(open.value.text, find.query, find.caseSensitive)
        if (matches.isEmpty()) {
            _state.update { it.copy(find = it.find.copy(matchCount = 0, currentIndex = -1)) }
            post("No matches for \"${find.query}\".", isError = false)
            return
        }
        val index = if (forward) FindEngine.nextIndex(matches, open.value.selMax)
        else FindEngine.previousIndex(matches, open.value.selMin)
        val range = matches[index]
        applyProgrammatic(open.path, open.value.copy(selStart = range.first, selEnd = range.last + 1), recordHistory = false)
        _state.update { it.copy(find = it.find.copy(matchCount = matches.size, currentIndex = index)) }
    }

    /** Recomputes the match count after the text, query or active file changed. */
    private fun refreshFind() {
        val s = _state.value
        if (!s.find.visible || s.find.query.isEmpty()) {
            if (s.find.matchCount != 0 || s.find.currentIndex != -1) {
                _state.update { it.copy(find = it.find.copy(matchCount = 0, currentIndex = -1)) }
            }
            return
        }
        val text = s.activeFile?.value?.text.orEmpty()
        val count = FindEngine.findAll(text, s.find.query, s.find.caseSensitive).size
        _state.update { it.copy(find = it.find.copy(matchCount = count)) }
    }

    // ------------------------------------------------------------------ explorer: search, sort, recent, preview

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setSort(sort: ExplorerSort) {
        _state.update { it.copy(sort = sort) }
    }

    fun clearRecent() {
        recent = RecentFiles()
        saveRecent()
    }

    private fun touchRecent(path: String) {
        recent = recent.touched(path)
        saveRecent()
    }

    private fun saveRecent() {
        _state.update { it.copy(recentFiles = recent.paths) }
        try {
            recentStore.save(projectId, recent)
        } catch (e: Exception) {
            // The recent list is a convenience; failing to remember it must never break the editor.
        }
    }

    /** Reads the file and shows it read-only. Open files show their current (possibly unsaved) text. */
    fun previewFile(path: String) {
        _state.update { it.copy(preview = PreviewUi(path)) }
        viewModelScope.launch {
            val size = _state.value.files.firstOrNull { it.path == path }?.sizeBytes ?: 0L
            val result: PreviewUi = try {
                if (FileTypes.isBinary(path)) {
                    PreviewUi(path, loading = false, preview = FilePreviewBuilder.unavailable(path, size, "Binary file: there is no text to preview."))
                } else if (size > SourceFileRepository.MAX_FILE_BYTES) {
                    PreviewUi(path, loading = false, preview = FilePreviewBuilder.unavailable(path, size, "Too large to preview (limit ${SourceFileRepository.MAX_FILE_BYTES / 1024} KB)."))
                } else {
                    val open = _state.value.openFiles.firstOrNull { it.path == path }
                    val text = open?.value?.text ?: files.readFile(projectId, path)
                    PreviewUi(path, loading = false, preview = FilePreviewBuilder.build(path, text, size))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PreviewUi(path, loading = false, error = e.toUserMessage())
            }
            // Ignore the answer if the user closed the sheet or asked for another file meanwhile.
            _state.update { if (it.preview?.path == path) it.copy(preview = result) else it }
        }
    }

    fun closePreview() {
        _state.update { it.copy(preview = null) }
    }

    // ------------------------------------------------------------------ messages & helpers

    fun consumeMessage() {
        _message.value = null
    }

    private fun post(text: String, isError: Boolean) {
        _message.value = EditorMessage(++messageCounter, text, isError)
    }

    private fun history(path: String): UndoHistory = histories.getOrPut(path) { UndoHistory() }

    private fun replaceOpen(path: String, transform: (OpenFile) -> OpenFile) {
        _state.update { s -> s.copy(openFiles = s.openFiles.map { if (it.path == path) transform(it) else it }) }
    }
}
