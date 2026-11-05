package com.almus.appcreatorpro.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.util.Formatters

/** Progress bar plus a one-line description. Indeterminate while the size is unknown. */
@Composable
fun TransferProgressBlock(progress: TransferProgress?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val fraction = progress?.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (progress != null) {
            val detail = buildString {
                append(progress.phase.label)
                if (progress.files > 0) append(" - ${progress.files} files")
                if (progress.doneBytes > 0L) {
                    append(" - ${Formatters.formatBytes(progress.doneBytes)}")
                    progress.totalBytes?.let { append(" of ${Formatters.formatBytes(it)}") }
                }
            }
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            progress.currentName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Modal progress for export / backup with a Cancel button. */
@Composable
fun TransferProgressDialog(title: String, progress: TransferProgress?, onCancel: (() -> Unit)?) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = { TransferProgressBlock(progress) },
        confirmButton = {
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
