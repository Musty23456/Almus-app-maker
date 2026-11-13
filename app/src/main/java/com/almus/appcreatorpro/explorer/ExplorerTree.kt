package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.domain.model.SourceFileEntry
import java.util.TreeMap

enum class ExplorerSort(val label: String) { NAME("Name"), SIZE("Size"), TYPE("Type") }

/** One visible row of the project explorer. */
sealed interface ExplorerRow {
    val path: String
    val name: String
    val depth: Int

    data class Folder(
        override val path: String,
        override val name: String,
        override val depth: Int,
        val expanded: Boolean,
        /** Files anywhere below this folder. */
        val fileCount: Int,
        val totalBytes: Long,
        val containsImportant: Boolean,
    ) : ExplorerRow

    data class File(
        override val path: String,
        override val name: String,
        override val depth: Int,
        val sizeBytes: Long,
        val kind: FileKind,
        val important: Boolean,
    ) : ExplorerRow
}

/**
 * Builds the explorer rows from the flat file list: folders first, then files, with folder totals.
 * With a search query only matching files (and the folders above them) are shown, all folders open.
 */
object ExplorerTree {

    private class Node(val path: String) {
        val folders = TreeMap<String, Node>(compareBy<String>({ it.lowercase() }, { it }))
        val files = ArrayList<SourceFileEntry>()
        var fileCount = 0
        var totalBytes = 0L
        var important = false
    }

    fun rows(
        entries: List<SourceFileEntry>,
        collapsedFolders: Set<String>,
        query: String = "",
        sort: ExplorerSort = ExplorerSort.NAME,
    ): List<ExplorerRow> {
        val searching = query.isNotBlank()
        val shown = if (searching) FileSearch.search(entries, query, limit = Int.MAX_VALUE) else entries
        val root = Node("")
        for (entry in shown) {
            val parts = entry.path.split('/')
            var node = root
            for (i in 0 until parts.size - 1) {
                val folderPath = parts.subList(0, i + 1).joinToString("/")
                node = node.folders.getOrPut(parts[i]) { Node(folderPath) }
            }
            node.files += entry
        }
        aggregate(root)
        val out = ArrayList<ExplorerRow>()
        emit(root, 0, if (searching) emptySet() else collapsedFolders, sort, out)
        return out
    }

    private fun aggregate(node: Node) {
        var count = node.files.size
        var bytes = node.files.sumOf { it.sizeBytes }
        var important = node.files.any { ProtectedFiles.isProtected(it.path) }
        for (child in node.folders.values) {
            aggregate(child)
            count += child.fileCount
            bytes += child.totalBytes
            important = important || child.important
        }
        node.fileCount = count
        node.totalBytes = bytes
        node.important = important
    }

    private fun emit(node: Node, depth: Int, collapsed: Set<String>, sort: ExplorerSort, out: MutableList<ExplorerRow>) {
        val folders = when (sort) {
            ExplorerSort.SIZE -> node.folders.entries.sortedByDescending { it.value.totalBytes }
            else -> node.folders.entries.toList()
        }
        for ((name, child) in folders) {
            val expanded = child.path !in collapsed
            out += ExplorerRow.Folder(child.path, name, depth, expanded, child.fileCount, child.totalBytes, child.important)
            if (expanded) emit(child, depth + 1, collapsed, sort, out)
        }
        val files = when (sort) {
            ExplorerSort.NAME -> node.files.sortedWith(compareBy({ it.name.lowercase() }, { it.name }))
            ExplorerSort.SIZE -> node.files.sortedWith(compareByDescending<SourceFileEntry> { it.sizeBytes }.thenBy { it.name.lowercase() })
            ExplorerSort.TYPE -> node.files.sortedWith(
                compareBy<SourceFileEntry>({ FileTypes.recognize(it.path).label }, { it.name.lowercase() }),
            )
        }
        for (entry in files) {
            out += ExplorerRow.File(
                path = entry.path,
                name = entry.name,
                depth = depth,
                sizeBytes = entry.sizeBytes,
                kind = FileTypes.recognize(entry.path),
                important = ProtectedFiles.isProtected(entry.path),
            )
        }
    }
}
