package com.almus.appcreatorpro.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

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
                _state.update { it.copy(isLoading = false, project = project, files = list) }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            post(e.toUserMessage(), isError = true)
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
        val clean = path.trim()
        viewModelScope.launch {
            try {
                files.createFile(projectId, clean, "")
                refreshFiles()
                openFileInternal(clean)
                post("Created $clean", isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(e.toUserMessage(), isError = true)
            }
        }
    }

    /** Renames or moves a file. An open (even modified) buffer follows the file to its new path. */
    fun renameFile(from: String, to: String) {
        val target = to.trim()
        viewModelScope.launch {
            try {
                files.renameFile(projectId, from, target)
                _state.update { s ->
                    s.copy(
                        openFiles = s.openFiles.map { if (it.path == from) it.copy(path = target) else it },
                        activePath = if (s.activePath == from) target else s.activePath,
                    )
                }
                histories.remove(from)?.let { histories[target] = it }
                refreshFiles()
                post("Renamed to $target", isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(e.toUserMessage(), isError = true)
            }
        }
    }

    fun deleteFile(path: String) {
        viewModelScope.launch {
            try {
                files.deleteFile(projectId, path)
                closeFile(path)
                refreshFiles()
                post("Deleted ${path.substringAfterLast('/')}", isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post(e.toUserMessage(), isError = true)
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
