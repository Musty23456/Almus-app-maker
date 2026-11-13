package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.domain.model.SourceFileEntry
import java.util.TreeMap

data class KindStat(val kind: FileKind, val fileCount: Int, val totalBytes: Long)

data class FolderStat(val path: String, val fileCount: Int, val totalBytes: Long)

/** Numbers behind the "Structure" view. */
data class ProjectStructure(
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
    /** Deepest folder nesting (a file in the root has depth 0). */
    val maxDepth: Int,
    /** Most files first. */
    val byKind: List<KindStat>,
    /** Biggest files first. */
    val largest: List<SourceFileEntry>,
    /** Top-level folders, biggest first; files in the root are counted under "(root)". */
    val topFolders: List<FolderStat>,
)

object ProjectStructureAnalyzer {

    fun analyze(entries: List<SourceFileEntry>, largestCount: Int = 5): ProjectStructure {
        val folders = HashSet<String>()
        var maxDepth = 0
        for (e in entries) {
            folders += PathOps.ancestors(e.path)
            maxDepth = maxOf(maxDepth, e.path.count { it == '/' })
        }
        val byKind = entries.groupBy { FileTypes.recognize(it.path) }
            .map { (kind, list) -> KindStat(kind, list.size, list.sumOf { it.sizeBytes }) }
            .sortedWith(compareByDescending<KindStat> { it.fileCount }.thenBy { it.kind.label })
        val top = entries.groupBy { if (it.path.contains('/')) it.path.substringBefore('/') else ROOT }
            .map { (folder, list) -> FolderStat(folder, list.size, list.sumOf { it.sizeBytes }) }
            .sortedWith(compareByDescending<FolderStat> { it.totalBytes }.thenBy { it.path.lowercase() })
        return ProjectStructure(
            fileCount = entries.size,
            folderCount = folders.size,
            totalBytes = entries.sumOf { it.sizeBytes },
            maxDepth = maxDepth,
            byKind = byKind,
            largest = entries.sortedWith(compareByDescending<SourceFileEntry> { it.sizeBytes }.thenBy { it.path.lowercase() })
                .take(largestCount),
            topFolders = top,
        )
    }

    const val ROOT = "(root)"

    private class Node {
        val folders = TreeMap<String, Node>(compareBy<String>({ it.lowercase() }, { it }))
        val files = ArrayList<String>()
    }

    /** A text drawing of the folder tree (├── └── │), cut after [maxLines] lines. */
    fun asciiTree(projectName: String, entries: List<SourceFileEntry>, maxLines: Int = 400): String {
        val root = Node()
        for (e in entries) {
            val parts = e.path.split('/')
            var node = root
            for (i in 0 until parts.size - 1) node = node.folders.getOrPut(parts[i]) { Node() }
            node.files += parts.last()
        }
        val lines = ArrayList<String>()
        lines += "$projectName/"
        draw(root, "", lines)
        if (lines.size <= maxLines) return lines.joinToString("\n")
        val hidden = lines.size - maxLines
        return lines.take(maxLines).joinToString("\n") + "\n… $hidden more line(s)"
    }

    private fun draw(node: Node, prefix: String, out: MutableList<String>) {
        val files = node.files.sortedWith(compareBy({ it.lowercase() }, { it }))
        val total = node.folders.size + files.size
        var index = 0
        for ((name, child) in node.folders) {
            index++
            val last = index == total
            out += prefix + (if (last) "└── " else "├── ") + name + "/"
            draw(child, prefix + (if (last) "    " else "│   "), out)
        }
        for (file in files) {
            index++
            out += prefix + (if (index == total) "└── " else "├── ") + file
        }
    }
}
