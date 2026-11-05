package com.almus.appcreatorpro.editor

import com.almus.appcreatorpro.domain.model.SourceFileEntry
import java.util.TreeMap

/** One visible row of the file explorer. */
sealed interface TreeRow {
    val path: String
    val name: String
    val depth: Int

    data class Folder(
        override val path: String,
        override val name: String,
        override val depth: Int,
        val expanded: Boolean,
    ) : TreeRow

    data class File(
        override val path: String,
        override val name: String,
        override val depth: Int,
    ) : TreeRow
}

/** Turns a flat list of file paths into the rows of an expandable tree (folders first, A-Z). */
object FileTree {

    private class Node(val path: String) {
        val folders = TreeMap<String, Node>(compareBy<String>({ it.lowercase() }, { it }))
        val files = ArrayList<String>()
    }

    fun rows(entries: List<SourceFileEntry>, collapsedFolders: Set<String>): List<TreeRow> {
        val root = Node("")
        for (entry in entries) {
            val parts = entry.path.split('/')
            var node = root
            for (i in 0 until parts.size - 1) {
                val folderPath = parts.subList(0, i + 1).joinToString("/")
                node = node.folders.getOrPut(parts[i]) { Node(folderPath) }
            }
            node.files += parts.last()
        }
        val rows = ArrayList<TreeRow>()
        emit(root, 0, collapsedFolders, rows)
        return rows
    }

    private fun emit(node: Node, depth: Int, collapsed: Set<String>, out: MutableList<TreeRow>) {
        for ((name, child) in node.folders) {
            val expanded = child.path !in collapsed
            out += TreeRow.Folder(child.path, name, depth, expanded)
            if (expanded) emit(child, depth + 1, collapsed, out)
        }
        for (file in node.files.sortedWith(compareBy({ it.lowercase() }, { it }))) {
            val path = if (node.path.isEmpty()) file else node.path + "/" + file
            out += TreeRow.File(path, file, depth)
        }
    }
}
