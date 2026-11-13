package com.almus.appcreatorpro.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier

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
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                isError = error != null,
                supportingText = { Text(error ?: hint.orEmpty()) },
            )
        },
        confirmButton = {
            AlmusButton(text = confirmLabel, onClick = { onConfirm(value) }, enabled = canConfirm)
        },
        dismissButton = { AlmusButton(text = "Cancel", onClick = onDismiss, style = AlmusButtonStyle.Text) },
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
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = if (destructive) {
            { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        } else null,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            AlmusButton(
                text = confirmLabel,
                onClick = onConfirm,
                style = if (destructive) AlmusButtonStyle.Danger else AlmusButtonStyle.Primary,
            )
        },
        dismissButton = { AlmusButton(text = "Cancel", onClick = onDismiss, style = AlmusButtonStyle.Text) },
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
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Unsaved changes") },
        text = { Text(message) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                AlmusButton(text = saveLabel, onClick = onSave)
                TextButton(onClick = onDiscard) {
                    Text(discardLabel, color = MaterialTheme.colorScheme.error)
                }
                AlmusButton(text = "Keep editing", onClick = onCancel, style = AlmusButtonStyle.Text)
            }
        },
    )
}

/** Phase 7: opens this app's notification page in the Android settings (the only place that can unblock them). */
fun openAppNotificationSettings(context: android.content.Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        // No settings screen to open: nothing else can be done from here.
    }
}
