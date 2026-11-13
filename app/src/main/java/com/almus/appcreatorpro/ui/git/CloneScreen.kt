package com.almus.appcreatorpro.ui.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneScreen(onBack: () -> Unit, onOpenProject: (String) -> Unit) {
    val vm: CloneViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.createdProjectId) { s.createdProjectId?.let { vm.navigated(); onOpenProject(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clone repository") },
                navigationIcon = { IconButton(onClick = onBack, enabled = !s.running) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(value = s.url, onValueChange = vm::setUrl, label = { Text("https:// repository address") }, singleLine = true, enabled = !s.running, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(value = s.branch, onValueChange = vm::setBranch, label = { Text("Branch (optional)") }, singleLine = true, enabled = !s.running, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text(
                    "A new project is created with the full history. Your GitHub sign-in is used only for github.com addresses; nothing secret is saved in the repository.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                if (s.running) {
                    val p = s.progress
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p?.task?.takeIf { it.isNotBlank() } ?: "Cloning…", style = MaterialTheme.typography.bodySmall)
                        if (p != null && p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                } else {
                    Button(onClick = vm::clone, enabled = s.url.isNotBlank()) { Text("Clone") }
                }
            }
            s.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            if (s.githubUser != null) {
                item { Text("Your GitHub repositories", style = MaterialTheme.typography.titleSmall) }
                items(s.repos, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth().clickable(enabled = !s.running) { vm.pick(r) }) {
                        Column(Modifier.padding(10.dp)) {
                            Text(r.fullName + if (r.isPrivate) "  (private)" else "", style = MaterialTheme.typography.bodyMedium)
                            r.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                s.reposError?.let { e -> item { Text(e, style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}
