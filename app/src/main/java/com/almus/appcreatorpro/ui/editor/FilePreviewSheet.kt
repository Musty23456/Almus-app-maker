package com.almus.appcreatorpro.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almus.appcreatorpro.editor.EditorThemes
import com.almus.appcreatorpro.editor.TokenType
import com.almus.appcreatorpro.explorer.FilePreview
import com.almus.appcreatorpro.util.Formatters

/** Phase 25: read-only preview of one file with syntax colors. Editing happens in the editor ([onEdit]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewSheet(
    preview: PreviewUi,
    onEdit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preview.path.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val p = preview.preview
                    if (p != null) {
                        val lines = if (p.hasText) " · ${p.totalLines} line(s)" else ""
                        Text(
                            text = "${p.kind.label} · ${Formatters.formatBytes(p.sizeBytes)}$lines",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = preview.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onEdit(preview.path) }, enabled = preview.preview?.hasText == true) { Text("Edit") }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            when {
                preview.loading -> Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                preview.error != null -> Text(preview.error, color = MaterialTheme.colorScheme.error)
                preview.preview != null -> PreviewBody(preview.preview)
            }
        }
    }
}

@Composable
private fun PreviewBody(preview: FilePreview) {
    if (!preview.hasText) {
        Text(preview.note.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (preview.text.isEmpty()) {
        Text("This file is empty.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val dark = isSystemInDarkTheme()
    val theme = if (dark) EditorThemes.ALMUS_DARK else EditorThemes.ALMUS_LIGHT
    val annotated = remember(preview, dark) { highlight(preview, theme) }
    val gutter = remember(preview) { (1..preview.shownLines).joinToString("\n") }
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = gutter,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.End,
                    color = Color(theme.gutterForeground),
                    softWrap = false,
                )
                Text(
                    text = annotated,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = Color(theme.foreground),
                    softWrap = false,
                )
            }
        }
        if (preview.truncated) {
            Text(
                text = "Showing the first ${preview.shownLines} of ${preview.totalLines} lines. Open the file in the editor to see everything.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun highlight(preview: FilePreview, theme: com.almus.appcreatorpro.editor.EditorTheme): AnnotatedString =
    buildAnnotatedString {
        append(preview.text)
        for (token in preview.tokens) {
            if (token.start < 0 || token.end > preview.text.length || token.start >= token.end) continue
            val color = Color(theme.colorFor(token.type))
            val style = if (token.type == TokenType.COMMENT) SpanStyle(color = color, fontStyle = FontStyle.Italic) else SpanStyle(color = color)
            addStyle(style, token.start, token.end)
        }
    }
