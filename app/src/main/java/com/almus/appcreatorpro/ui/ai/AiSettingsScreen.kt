package com.almus.appcreatorpro.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.ConfirmDialog

/** Backend address, access code, connection test and model choice. There is no place to enter a Gemini key: it lives on the backend. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onBack: () -> Unit, viewModel: AiSettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var url by rememberSaveable(state.url) { mutableStateOf(state.url) }
    // The code is never restored from storage and never shown again: the field is for entering a new one only.
    var code by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI backend") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Gemini is called by your own ALMUS backend, not by this app. The Gemini API key is stored only on the backend. " +
                    "This app stores the backend address and an access code (encrypted on this phone). See the API configuration guide in the project's backend/docs folder.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Backend address") }, placeholder = { Text("https://your-backend.example.com") },
            )
            OutlinedTextField(
                value = code, onValueChange = { code = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (state.hasAccessCode) "Access code (saved - enter a new one to replace)" else "Access code") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.save(url, code); code = "" }) { Text("Save") }
                OutlinedButton(onClick = { viewModel.testConnection() }, enabled = !state.testing && state.url.isNotBlank() && state.hasAccessCode) {
                    Text(if (state.testing) "Testing..." else "Test connection")
                }
            }
            state.message?.let {
                Text(it, color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.models.isNotEmpty()) {
                Text("Model", style = MaterialTheme.typography.titleMedium)
                Text("The backend decides which models are allowed. The default is used when none is chosen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.models.forEach { id ->
                    val isDefault = id == state.defaultModel
                    FilterChip(
                        selected = state.selectedModel == id || (state.selectedModel == null && isDefault),
                        onClick = { viewModel.selectModel(if (isDefault) null else id) },
                        label = { Text(if (isDefault) "$id (default)" else id) },
                    )
                }
            }
            if (state.url.isNotBlank() || state.hasAccessCode) {
                TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect and remove the access code", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDisconnect) ConfirmDialog(
        title = "Disconnect?", message = "The backend address and access code are removed from this phone. Your chats and projects stay.",
        confirmLabel = "Disconnect", destructive = true,
        onConfirm = { viewModel.disconnect(); code = ""; confirmDisconnect = false }, onDismiss = { confirmDisconnect = false },
    )
}
