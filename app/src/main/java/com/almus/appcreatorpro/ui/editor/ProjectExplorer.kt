package com.almus.appcreatorpro.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almus.appcreatorpro.domain.model.SourceFileEntry
import com.almus.appcreatorpro.explorer.ExplorerRow
import com.almus.appcreatorpro.explorer.ExplorerSort
import com.almus.appcreatorpro.explorer.FileKind
import com.almus.appcreatorpro.explorer.FileTypes
import com.almus.appcreatorpro.explorer.PathOps
import com.almus.appcreatorpro.explorer.ProjectStructureAnalyzer
import com.almus.appcreatorpro.explorer.ProtectedFiles
import com.almus.appcreatorpro.util.Formatters

private val TAB_LABELS = listOf("Files", "Recent", "Structure")

/** Phase 25: contents of the navigation drawer. Replaces the simple file list with search, recent files and a structure view. */
@Composable
fun ProjectExplorer(
    projectName: String,
    files: List<SourceFileEntry>,
    rows: List<ExplorerRow>,
    recentFiles: List<String>,
    activePath: String?,
    dirtyPaths: Set<String>,
    searchQuery: String,
    sort: ExplorerSort,
    onSearchChange: (String) -> Unit,
    onSortChange: (ExplorerSort) -> Unit,
    onOpenFile: (String) -> Unit,
    onPreviewFile: (String) -> Unit,
    onToggleFolder: (String) -> Unit,
    /** Argument: the folder prefix to start from ("" for the project root, "src/" for a folder). */
    onNewFile: (String) -> Unit,
    onNewFolder: (String) -> Unit,
    onRenameFile: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onDuplicateFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onRenameProject: () -> Unit,
    onClearRecent: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("PROJECT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = projectName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${files.size} file(s) · ${Formatters.formatBytes(files.sumOf { it.sizeBytes })}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRenameProject) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename project")
            }
        }
        TabRow(selectedTabIndex = tab) {
            TAB_LABELS.forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> FilesTab(
                rows = rows,
                hasFiles = files.isNotEmpty(),
                activePath = activePath,
                dirtyPaths = dirtyPaths,
                searchQuery = searchQuery,
                sort = sort,
                onSearchChange = onSearchChange,
                onSortChange = onSortChange,
                onOpenFile = onOpenFile,
                onPreviewFile = onPreviewFile,
                onToggleFolder = onToggleFolder,
                onNewFile = onNewFile,
                onNewFolder = onNewFolder,
                onRenameFile = onRenameFile,
                onRenameFolder = onRenameFolder,
                onDuplicateFile = onDuplicateFile,
                onDeleteFile = onDeleteFile,
                onDeleteFolder = onDeleteFolder,
            )
            1 -> RecentTab(
                recentFiles = recentFiles,
                sizes = remember(files) { files.associate { it.path to it.sizeBytes } },
                activePath = activePath,
                dirtyPaths = dirtyPaths,
                onOpenFile = onOpenFile,
                onPreviewFile = onPreviewFile,
                onClear = onClearRecent,
            )
            else -> StructureTab(projectName = projectName, files = files, onCopyText = onCopyText)
        }
    }
}

// ---------------------------------------------------------------------------------------------- files tab

@Composable
private fun FilesTab(
    rows: List<ExplorerRow>,
    hasFiles: Boolean,
    activePath: String?,
    dirtyPaths: Set<String>,
    searchQuery: String,
    sort: ExplorerSort,
    onSearchChange: (String) -> Unit,
    onSortChange: (ExplorerSort) -> Unit,
    onOpenFile: (String) -> Unit,
    onPreviewFile: (String) -> Unit,
    onToggleFolder: (String) -> Unit,
    onNewFile: (String) -> Unit,
    onNewFolder: (String) -> Unit,
    onRenameFile: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onDuplicateFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    val searching = searchQuery.isNotBlank()
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            singleLine = true,
            placeholder = { Text("Search files (e.g. main  .kt)") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onNewFile("") }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("File")
            }
            TextButton(onClick = { onNewFolder("") }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Folder")
            }
            Spacer(modifier = Modifier.weight(1f))
            SortMenu(sort = sort, onSortChange = onSortChange)
        }
        HorizontalDivider()
        when {
            !hasFiles -> EmptyNote("This project has no files yet. Tap \"File\" to add one.")
            rows.isEmpty() -> EmptyNote("No file matches \"${searchQuery.trim()}\".")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { (if (it is ExplorerRow.Folder) "d:" else "f:") + it.path }) { row ->
                    when (row) {
                        is ExplorerRow.Folder -> ExplorerFolderRow(
                            row = row,
                            showToggle = !searching,
                            onToggle = { onToggleFolder(row.path) },
                            onNewFile = { onNewFile(row.path + "/") },
                            onNewFolder = { onNewFolder(row.path + "/") },
                            onRename = { onRenameFolder(row.path) },
                            onDelete = { onDeleteFolder(row.path) },
                        )
                        is ExplorerRow.File -> ExplorerFileRow(
                            row = row,
                            subtitle = fileSubtitle(row, searching),
                            selected = row.path == activePath,
                            dirty = row.path in dirtyPaths,
                            indent = !searching,
                            onOpen = { onOpenFile(row.path) },
                            onPreview = { onPreviewFile(row.path) },
                            onRename = { onRenameFile(row.path) },
                            onDuplicate = { onDuplicateFile(row.path) },
                            onDelete = { onDeleteFile(row.path) },
                        )
                    }
                }
            }
        }
    }
}

private fun fileSubtitle(row: ExplorerRow.File, searching: Boolean): String {
    val size = Formatters.formatBytes(row.sizeBytes)
    val folder = PathOps.parent(row.path)
    return if (searching && folder.isNotEmpty()) "$folder · $size" else size
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun SortMenu(sort: ExplorerSort, onSortChange: (ExplorerSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("Sort: ${sort.label}")
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in ExplorerSort.values()) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        open = false
                        onSortChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun KindBadge(kind: FileKind) {
    Box(
        modifier = Modifier
            .width(36.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = kind.badge,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}

@Composable
private fun ExplorerFolderRow(
    row: ExplorerRow.Folder,
    showToggle: Boolean,
    onToggle: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = (8 + (if (showToggle) row.depth else 0) * 16).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (row.expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
            contentDescription = if (row.expanded) "Collapse ${row.name}" else "Expand ${row.name}",
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 4.dp)) {
            Text(
                text = row.name + "/",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${row.fileCount} file(s) · ${Formatters.formatBytes(row.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.containsImportant) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Contains important files",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for folder ${row.name}", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("New file here") }, onClick = { menuOpen = false; onNewFile() })
                DropdownMenuItem(text = { Text("New folder here") }, onClick = { menuOpen = false; onNewFolder() })
                DropdownMenuItem(text = { Text("Rename / move folder") }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text("Delete folder") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ExplorerFileRow(
    row: ExplorerRow.File,
    subtitle: String,
    selected: Boolean,
    dirty: Boolean,
    indent: Boolean,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = (12 + (if (indent) row.depth else 0) * 16).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBadge(row.kind)
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 8.dp)) {
            Text(
                text = (if (dirty) "• " else "") + row.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.important) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Important file",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FileMenu(
            name = row.name,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            onOpen = onOpen,
            onPreview = onPreview,
            onRename = onRename,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun FileMenu(
    name: String,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        IconButton(onClick = { onMenuOpenChange(true) }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Actions for $name", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
            DropdownMenuItem(text = { Text("Open in editor") }, onClick = { onMenuOpenChange(false); onOpen() })
            DropdownMenuItem(text = { Text("Preview") }, onClick = { onMenuOpenChange(false); onPreview() })
            DropdownMenuItem(text = { Text("Rename / move") }, onClick = { onMenuOpenChange(false); onRename() })
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { onMenuOpenChange(false); onDuplicate() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { onMenuOpenChange(false); onDelete() })
        }
    }
}

// ---------------------------------------------------------------------------------------------- recent tab

@Composable
private fun RecentTab(
    recentFiles: List<String>,
    sizes: Map<String, Long>,
    activePath: String?,
    dirtyPaths: Set<String>,
    onOpenFile: (String) -> Unit,
    onPreviewFile: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (recentFiles.isEmpty()) {
        EmptyNote("Files you open will appear here.")
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${recentFiles.size} recent file(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onClear) { Text("Clear") }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(recentFiles, key = { it }) { path ->
                val kind = FileTypes.recognize(path)
                val name = path.substringAfterLast('/')
                val folder = PathOps.parent(path)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFile(path) }
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KindBadge(kind)
                    Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 8.dp)) {
                        Text(
                            text = (if (path in dirtyPaths) "• " else "") + name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (path == activePath) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = (if (folder.isEmpty()) "project root" else folder) + " · " + Formatters.formatBytes(sizes[path] ?: 0L),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { onPreviewFile(path) }) { Text("Preview") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- structure tab

@Composable
private fun StructureTab(projectName: String, files: List<SourceFileEntry>, onCopyText: (String) -> Unit) {
    if (files.isEmpty()) {
        EmptyNote("The structure appears when the project has files.")
        return
    }
    val structure = remember(files) { ProjectStructureAnalyzer.analyze(files) }
    val tree = remember(files, projectName) { ProjectStructureAnalyzer.asciiTree(projectName, files) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "${structure.fileCount} files · ${structure.folderCount} folders · ${Formatters.formatBytes(structure.totalBytes)} · depth ${structure.maxDepth}",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionLabel("By file type")
        val maxKind = structure.byKind.maxOfOrNull { it.fileCount } ?: 1
        for (stat in structure.byKind) {
            BarRow(
                label = stat.kind.label,
                value = "${stat.fileCount} · ${Formatters.formatBytes(stat.totalBytes)}",
                fraction = stat.fileCount.toFloat() / maxKind,
            )
        }

        SectionLabel("Top-level folders (by size)")
        val maxFolder = structure.topFolders.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1L) ?: 1L
        for (folder in structure.topFolders) {
            BarRow(
                label = if (folder.path == ProjectStructureAnalyzer.ROOT) folder.path else folder.path + "/",
                value = "${folder.fileCount} file(s) · ${Formatters.formatBytes(folder.totalBytes)}",
                fraction = folder.totalBytes.toFloat() / maxFolder,
            )
        }

        SectionLabel("Largest files")
        for (entry in structure.largest) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(Formatters.formatBytes(entry.sizeBytes), style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Folder tree", modifier = Modifier.weight(1f))
            TextButton(onClick = { onCopyText(tree) }) { Text("Copy") }
        }
        Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                text = tree,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                softWrap = false,
            )
        }
        if (files.any { ProtectedFiles.isProtected(it.path) }) {
            Text(
                text = "Files marked with a lock are important configuration files. Deleting one needs you to type its name.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
private fun BarRow(label: String, value: String, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }
}
