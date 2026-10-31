package com.almus.appcreatorpro.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Scrollable row of open files. A dot marks files with unsaved changes. */
@Composable
fun FileTabs(
    openFiles: List<OpenFile>,
    activePath: String?,
    onSelect: (String) -> Unit,
    onClose: (OpenFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        openFiles.forEach { file ->
            val selected = file.path == activePath
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = (if (file.isDirty) "• " else "") + file.name,
                        modifier = Modifier
                            .clickable { onSelect(file.path) }
                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .clickable { onClose(file) }
                            .defaultMinSize(minWidth = 32.dp, minHeight = 36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close ${file.name}", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier
            .padding(horizontal = 3.dp, vertical = 4.dp)
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp),
    ) {
        Box(modifier = Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(text = label, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/** Quick-action row above the keyboard: file actions, edit actions and code symbols. */
@Composable
fun QuickActionBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canSave: Boolean,
    hasSelection: Boolean,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton("Save", enabled = canSave, onClick = onSave)
            KeyButton("Undo", enabled = canUndo, onClick = onUndo)
            KeyButton("Redo", enabled = canRedo, onClick = onRedo)
            KeyButton("Tab", onClick = onIndent)
            KeyButton("Untab", onClick = onOutdent)
            KeyButton("Copy", enabled = hasSelection, onClick = onCopy)
            KeyButton("Cut", enabled = hasSelection, onClick = onCut)
            KeyButton("Paste", onClick = onPaste)
            KeyButton("All", onClick = onSelectAll)
            for (symbol in SYMBOLS) KeyButton(symbol, onClick = { onInsert(symbol) })
        }
    }
}

private val SYMBOLS = listOf("{", "}", "(", ")", "[", "]", ";", ":", "\"", "'", "<", ">", "/", "=", "\\", "_", "-", "+", "*", "&", "|", "!", "#", "$")

/** Find (and optionally replace) bar. */
@Composable
fun FindBar(
    find: FindState,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = find.query,
                    onValueChange = onQueryChange,
                    label = { Text("Find") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPrevious, enabled = find.query.isNotEmpty()) { Text("Prev") }
                TextButton(onClick = onNext, enabled = find.query.isNotEmpty()) { Text("Next") }
                TextButton(onClick = onClose) { Text("Close") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = find.replacement,
                    onValueChange = onReplacementChange,
                    label = { Text("Replace with") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReplace, enabled = find.query.isNotEmpty()) { Text("Replace") }
                TextButton(onClick = onReplaceAll, enabled = find.query.isNotEmpty()) { Text("All") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = find.caseSensitive, onCheckedChange = onCaseSensitiveChange)
                Text("Match case", style = MaterialTheme.typography.bodySmall)
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = when {
                        find.query.isEmpty() -> ""
                        find.matchCount == 0 -> "No matches"
                        find.currentIndex >= 0 -> "${find.currentIndex + 1} of ${find.matchCount}"
                        else -> "${find.matchCount} match(es)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

/** Thin status line: language, caret position, selection size. */
@Composable
fun EditorStatusLine(
    languageName: String,
    line: Int,
    column: Int,
    selectedChars: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(languageName, style = style, color = color)
        Text("Ln $line, Col $column", style = style, color = color)
        if (selectedChars > 0) Text("$selectedChars selected", style = style, color = color)
    }
}
