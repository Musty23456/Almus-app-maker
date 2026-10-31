package com.almus.appcreatorpro.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.validation.FilePathValidator
import com.almus.appcreatorpro.domain.validation.ProjectNameValidator
import com.almus.appcreatorpro.editor.FileTree
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.ConfirmDialog
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.ErrorView
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.TextInputDialog
import com.almus.appcreatorpro.ui.components.UnsavedChangesDialog
import com.almus.appcreatorpro.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

private sealed interface EditorDialog {
    data class NewFile(val prefix: String) : EditorDialog
    data class RenameFile(val path: String) : EditorDialog
    data class DeleteFile(val path: String) : EditorDialog
    data object RenameProject : EditorDialog
    data class CloseTab(val path: String) : EditorDialog
    data object Leave : EditorDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectId: String,
    onBack: () -> Unit,
) {
    val viewModel: EditorViewModel = viewModel(
        key = "editor-$projectId",
        factory = AppViewModelProvider.editorFactory(projectId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.consumeMessage()
        }
    }

    // Safety net: whenever the app leaves the screen (home button, app switch, screen off) save work in progress.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.saveAll() }

    val requestLeave: () -> Unit = {
        if (state.hasUnsavedChanges) {
            dialog = EditorDialog.Leave
        } else {
            onBack()
        }
    }

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            state.find.visible -> viewModel.toggleFind()
            else -> requestLeave()
        }
    }

    val rows = remember(state.files, state.collapsedFolders) { FileTree.rows(state.files, state.collapsedFolders) }
    val dirtyPaths = remember(state.openFiles) { state.openFiles.filter { it.isDirty }.map { it.path }.toSet() }
    val active = state.activeFile

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                FileExplorer(
                    projectName = state.project?.name.orEmpty(),
                    rows = rows,
                    activePath = state.activePath,
                    dirtyPaths = dirtyPaths,
                    onOpenFile = { path ->
                        viewModel.openFile(path)
                        scope.launch { drawerState.close() }
                    },
                    onToggleFolder = viewModel::toggleFolder,
                    onNewFile = { prefix -> dialog = EditorDialog.NewFile(prefix) },
                    onRenameFile = { path -> dialog = EditorDialog.RenameFile(path) },
                    onDeleteFile = { path -> dialog = EditorDialog.DeleteFile(path) },
                    onRenameProject = { dialog = EditorDialog.RenameProject },
                )
            }
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.project?.name ?: "Editor",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            SaveStatus(
                                hasUnsaved = state.hasUnsavedChanges,
                                isSaving = state.isSaving,
                                visible = state.project != null,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = requestLeave) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Files")
                        }
                        IconButton(onClick = viewModel::toggleFind, enabled = active != null) {
                            Icon(Icons.Filled.Search, contentDescription = "Find and replace")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Save") },
                                    enabled = active?.isDirty == true,
                                    onClick = { menuOpen = false; viewModel.saveActive() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Save all") },
                                    enabled = state.hasUnsavedChanges,
                                    onClick = { menuOpen = false; viewModel.saveAll(silent = false) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename project") },
                                    onClick = { menuOpen = false; dialog = EditorDialog.RenameProject },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                when {
                    state.isLoading -> LoadingView(message = "Opening project...")
                    state.loadError != null -> ErrorView(message = state.loadError.orEmpty(), onRetry = viewModel::load)
                    else -> {
                        if (state.openFiles.isNotEmpty()) {
                            FileTabs(
                                openFiles = state.openFiles,
                                activePath = state.activePath,
                                onSelect = viewModel::selectTab,
                                onClose = { file ->
                                    if (file.isDirty) {
                                        dialog = EditorDialog.CloseTab(file.path)
                                    } else {
                                        viewModel.closeFile(file.path)
                                    }
                                },
                            )
                        }
                        if (state.find.visible && active != null) {
                            FindBar(
                                find = state.find,
                                onQueryChange = viewModel::setFindQuery,
                                onReplacementChange = viewModel::setReplacement,
                                onCaseSensitiveChange = viewModel::setCaseSensitive,
                                onNext = viewModel::findNext,
                                onPrevious = viewModel::findPrevious,
                                onReplace = viewModel::replaceCurrent,
                                onReplaceAll = viewModel::replaceAll,
                                onClose = viewModel::toggleFind,
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (active == null) {
                                EmptyView(
                                    icon = Icons.AutoMirrored.Filled.List,
                                    title = "No file open",
                                    message = "Open a file from the file explorer, or create a new one.",
                                    actionLabel = "Open files",
                                    onAction = { scope.launch { drawerState.open() } },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                CodeEditorSurface(
                                    path = active.path,
                                    value = active.value,
                                    revision = active.revision,
                                    language = active.language,
                                    onUserEdit = viewModel::onUserEdit,
                                    onIndentKey = { shift -> if (shift) viewModel.outdent() else viewModel.indent() },
                                )
                            }
                        }
                        if (active != null) {
                            val v = active.value
                            val before = v.text.substring(0, v.cursor)
                            EditorStatusLine(
                                languageName = active.language.displayName,
                                line = before.count { it == '\n' } + 1,
                                column = v.cursor - (before.lastIndexOf('\n') + 1) + 1,
                                selectedChars = v.selMax - v.selMin,
                            )
                            QuickActionBar(
                                canUndo = active.canUndo,
                                canRedo = active.canRedo,
                                canSave = active.isDirty,
                                hasSelection = !v.isCollapsed,
                                onSave = viewModel::saveActive,
                                onUndo = viewModel::undo,
                                onRedo = viewModel::redo,
                                onIndent = viewModel::indent,
                                onOutdent = viewModel::outdent,
                                onCopy = { if (!v.isCollapsed) clipboard.setText(AnnotatedString(v.selectedText)) },
                                onCut = {
                                    if (!v.isCollapsed) {
                                        clipboard.setText(AnnotatedString(v.selectedText))
                                        viewModel.insertText("")
                                    }
                                },
                                onPaste = { clipboard.getText()?.text?.let { viewModel.insertText(it) } },
                                onSelectAll = viewModel::selectAll,
                                onInsert = viewModel::insertText,
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    when (val current = dialog) {
        null -> Unit
        is EditorDialog.NewFile -> TextInputDialog(
            title = "New file",
            label = "File path",
            initialValue = current.prefix,
            confirmLabel = "Create",
            hint = "Use / to put it in a folder, e.g. src/Utils.kt",
            validate = { input ->
                FilePathValidator.validate(input).errorMessage
                    ?: if (state.files.any { it.path == input }) "A file with this path already exists." else null
            },
            onConfirm = { path ->
                dialog = null
                scope.launch { drawerState.close() }
                viewModel.createFile(path)
            },
            onDismiss = { dialog = null },
        )
        is EditorDialog.RenameFile -> TextInputDialog(
            title = "Rename or move file",
            label = "New path",
            initialValue = current.path,
            confirmLabel = "Rename",
            hint = "Change the path to move the file into another folder.",
            validate = { input ->
                FilePathValidator.validate(input).errorMessage
                    ?: when {
                        input == current.path -> "Enter a different name."
                        state.files.any { it.path == input } -> "A file with this path already exists."
                        else -> null
                    }
            },
            onConfirm = { newPath ->
                dialog = null
                viewModel.renameFile(current.path, newPath)
            },
            onDismiss = { dialog = null },
        )
        is EditorDialog.DeleteFile -> {
            val modified = current.path in dirtyPaths
            ConfirmDialog(
                title = "Delete file?",
                message = "\"${current.path}\" will be permanently deleted." +
                    if (modified) " Its unsaved changes will be lost too." else "",
                confirmLabel = "Delete",
                destructive = true,
                onConfirm = {
                    dialog = null
                    viewModel.deleteFile(current.path)
                },
                onDismiss = { dialog = null },
            )
        }
        EditorDialog.RenameProject -> TextInputDialog(
            title = "Rename project",
            label = "Project name",
            initialValue = state.project?.name.orEmpty(),
            confirmLabel = "Rename",
            validate = { ProjectNameValidator.validate(it).errorMessage },
            onConfirm = { newName ->
                dialog = null
                viewModel.renameProject(newName)
            },
            onDismiss = { dialog = null },
        )
        is EditorDialog.CloseTab -> UnsavedChangesDialog(
            message = "\"${current.path.substringAfterLast('/')}\" has changes that are not saved.",
            saveLabel = "Save and close",
            discardLabel = "Discard changes",
            onSave = {
                dialog = null
                viewModel.saveFile(current.path) { ok -> if (ok) viewModel.closeFile(current.path) }
            },
            onDiscard = {
                dialog = null
                viewModel.closeFile(current.path)
            },
            onCancel = { dialog = null },
        )
        EditorDialog.Leave -> UnsavedChangesDialog(
            message = "Some files have changes that are not saved.",
            saveLabel = "Save all and leave",
            discardLabel = "Discard and leave",
            onSave = {
                dialog = null
                viewModel.saveAll { ok -> if (ok) onBack() }
            },
            onDiscard = {
                dialog = null
                onBack()
            },
            onCancel = { dialog = null },
        )
    }
}

@Composable
private fun SaveStatus(hasUnsaved: Boolean, isSaving: Boolean, visible: Boolean) {
    if (!visible) return
    val (text, color) = when {
        isSaving -> "Saving..." to MaterialTheme.colorScheme.onSurfaceVariant
        hasUnsaved -> "Unsaved changes" to MaterialTheme.colorScheme.tertiary
        else -> "All changes saved" to SuccessGreen
    }
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
}
