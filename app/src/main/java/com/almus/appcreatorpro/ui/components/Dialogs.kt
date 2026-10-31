package com.almus.appcreatorpro.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

/**
 * Single-line text prompt. [validate] returns an error message or null; errors only show once
 * the user has typed something, and the confirm button stays disabled until the input is valid.
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmLabel: String,
    validate: (String) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
) {
    var value by rememberSaveable { mutableStateOf(initialValue) }
    val error = if (value.isEmpty()) null else validate(value)
    val canConfirm = value.isNotBlank() && validate(value) == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: hint.orEmpty()) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = canConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Three-way prompt shown before unsaved work could be lost. */
@Composable
fun UnsavedChangesDialog(
    message: String,
    saveLabel: String,
    discardLabel: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved changes") },
        text = { Text(message) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onSave) { Text(saveLabel) }
                TextButton(onClick = onDiscard) {
                    Text(discardLabel, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onCancel) { Text("Keep editing") }
            }
        },
    )
}
