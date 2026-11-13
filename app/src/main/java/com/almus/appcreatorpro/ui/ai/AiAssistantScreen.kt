package com.almus.appcreatorpro.ui.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.ai.AiProjectSession
import com.almus.appcreatorpro.ai.AiRole
import com.almus.appcreatorpro.ai.ChangeStatus
import com.almus.appcreatorpro.ai.ChatLanguage
import com.almus.appcreatorpro.ai.CodeReview
import com.almus.appcreatorpro.ai.FollowUpResolver
import com.almus.appcreatorpro.ai.ReviewVerdict
import com.almus.appcreatorpro.ai.SuggestionEngine
import com.almus.appcreatorpro.ai.ChatMarkdown
import com.almus.appcreatorpro.ai.ChatMode
import com.almus.appcreatorpro.ai.ChatTurn
import com.almus.appcreatorpro.ai.TurnState
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.ConfirmDialog
import com.almus.appcreatorpro.ui.components.LoadingView

/** The AI assistant of one project: Chat, Edit, Review and Changes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenDebug: () -> Unit,
    onOpenAgent: () -> Unit = {},
) {
    val vm: AiAssistantViewModel = viewModel(key = "ai-$projectId", factory = AppViewModelProvider.aiAssistantFactory(projectId))
    val session by vm.session.collectAsStateWithLifecycle()
    val availability by vm.availability.collectAsStateWithLifecycle()
    val paths by vm.paths.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI assistant") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = onOpenAgent) { Text("Agent") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = session
        if (s == null) {
            LoadingView(modifier = Modifier.padding(padding), message = "Opening...")
            return@Scaffold
        }
        val entries by s.changes.entries.collectAsStateWithLifecycle()
        val pending = entries.count { it.status == ChangeStatus.PENDING }
        Column(Modifier.padding(padding).fillMaxSize()) {
            (availability as? AiAvailability.NotReady)?.let { n ->
                Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(n.reason, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onOpenSettings) { Text("Set up AI") }
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                listOf("Chat", "Edit", "Review", if (pending > 0) "Changes ($pending)" else "Changes").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> ChatTab(s, vm, paths, onOpenDebug)
                1 -> EditTab(s, paths, onShowChanges = { tab = 3 })
                2 -> ReviewTab(s, paths, onOpenFile)
                else -> ChangesTab(s, vm, onOpenFile)
            }
        }
    }
}

@Composable
private fun ChatTab(session: AiProjectSession, vm: AiAssistantViewModel, paths: List<String>, onOpenDebug: () -> Unit) {
    val state by session.chat.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ChatMode.AUTO) }
    var uiLanguage by rememberSaveable { mutableStateOf(ChatLanguage.ENGLISH) }
    var attached by remember { mutableStateOf(listOf<String>()) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val review by vm.codeReview.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val turns = state.turns
    val smart = mode == ChatMode.AUTO
    // Phase 24: what Smart mode understood, which files a follow-up would carry over, and which files might matter.
    val guess = remember(input, attached, turns, smart) { if (smart && input.isNotBlank()) session.chat.previewIntent(input, attached.size) else null }
    val carry = remember(input, attached, turns, paths, guess) {
        if (guess == null) null else FollowUpResolver.carryOver(input, turns, attached, paths.toSet(), guess.intent)
    }
    val fileHints = remember(input, attached, paths, smart) { if (smart && input.trim().length >= 4) SuggestionEngine.suggestFiles(input, paths, attached) else emptyList() }
    val suggestions = remember(uiLanguage, profile, turns, attached, state.busy) {
        if (state.busy) emptyList() else SuggestionEngine.suggest(uiLanguage, profile, turns, attached)
    }

    LaunchedEffect(turns.size, turns.lastOrNull()?.text?.length) {
        if (turns.isNotEmpty()) listState.scrollToItem(turns.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (turns.isEmpty()) {
                item {
                    Text(
                        if (uiLanguage == ChatLanguage.HAUSA) {
                            "Gaya wa AI abin da kake so, da Hausa ko English: ƙirƙiri code, bayyana file, nemo bugs, inganta ko sake tsara code. " +
                                "Ka haɗa files domin AI ya gan su - kullum za ka ga files ɗin da ake aika wa."
                        } else {
                            "Say what you need in plain English or Hausa: create a screen, explain a file, find bugs, optimize or refactor. " +
                                "Attach files so the AI can see them - you always see which files are sent."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(turns, key = { it.id }) { t ->
                TurnView(
                    turn = t,
                    isLast = t.id == turns.lastOrNull()?.id,
                    busy = state.busy,
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                    onRegenerate = { session.chat.regenerate() },
                    onApply = { vm.reviewChat(t.id) },
                )
            }
        }
        state.notice?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) }
            }
            if (suggestions.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { uiLanguage = if (uiLanguage == ChatLanguage.ENGLISH) ChatLanguage.HAUSA else ChatLanguage.ENGLISH }) { Text(uiLanguage.label) }
                    suggestions.forEach { sg -> FilterChip(selected = false, onClick = { input = sg.prompt }, label = { Text(sg.label) }) }
                }
            }
            if (fileHints.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Attach?", style = MaterialTheme.typography.labelSmall)
                    fileHints.forEach { f -> FilterChip(selected = false, onClick = { attached = attached + f }, label = { Text(f.substringAfterLast('/')) }) }
                }
            }
            if (guess != null) {
                Text(
                    "Understood as: ${guess.intent.labelFor(guess.language)}" + if (guess.language == ChatLanguage.HAUSA) " (Hausa)" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                )
                if (carry != null && carry.isFollowUp) {
                    Text(
                        "Follow-up: the current text of ${carry.inherited.joinToString { it.substringAfterLast('/') }} from your last exchange is sent again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (guess.intent.needsExistingCode && attached.isEmpty()) {
                    Text("This is about existing code: attach the file so the AI can see it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = true }) { Text(if (attached.isEmpty()) "Attach files" else "Files (${attached.size})") }
                OutlinedButton(onClick = onOpenDebug) { Text("Build errors") }
                TextButton(onClick = { confirmClear = true }, enabled = turns.isNotEmpty() && !state.busy) { Text("Clear") }
            }
            if (attached.isNotEmpty()) {
                Text(
                    "Sent with your message: ${attached.joinToString { it.substringAfterLast('/') }} (secrets removed first)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(mode.hint) }, maxLines = 5,
                )
                if (state.busy) {
                    Button(onClick = { session.chat.cancel() }) { Text("Stop") }
                } else {
                    Button(onClick = { if (session.chat.send(input, mode, attached)) { input = ""; if (mode != ChatMode.EDIT) attached = emptyList() } }, enabled = input.isNotBlank()) { Text("Send") }
                }
            }
        }
    }

    review?.let { r -> CodeReviewDialog(r, onDismiss = { vm.dismissReview() }, onConfirm = { vm.confirmReview(it) }) }
    if (picking) FilePickerDialog(paths, attached, onDismiss = { picking = false }, onDone = { attached = it; picking = false })
    if (confirmClear) ConfirmDialog(
        title = "Clear conversation?", message = "The chat history of this project is deleted from this phone. Applied changes are not affected.",
        confirmLabel = "Clear", destructive = true, onConfirm = { vm.clearChat(); confirmClear = false }, onDismiss = { confirmClear = false },
    )
}

@Composable
private fun TurnView(turn: ChatTurn, isLast: Boolean, busy: Boolean, onCopy: (String) -> Unit, onRegenerate: () -> Unit, onApply: () -> Unit) {
    val mine = turn.role == AiRole.USER
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mine) {
                SelectionContainer { Text(turn.text) }
                if (turn.contextPaths.isNotEmpty()) {
                    Text(
                        (if (turn.inheritedContext) "Files (carried over from the last exchange): " else "Files: ") + turn.contextPaths.joinToString { it.substringAfterLast('/') },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                val understood = turn.intent
                if (understood != null) {
                    Text("Understood as: ${understood.labelFor(turn.language ?: ChatLanguage.ENGLISH)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (turn.text.isEmpty() && turn.state == TurnState.STREAMING) Text("Thinking...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChatMarkdown.split(turn.text).forEach { block ->
                    when (block) {
                        is ChatMarkdown.Block.Text -> SelectionContainer { Text(block.text, style = MaterialTheme.typography.bodyMedium) }
                        is ChatMarkdown.Block.Code -> CodeBlockView(
                            code = block.code, language = ChatMarkdown.languageFor(block.language, block.path), onCopy = onCopy, title = block.path,
                        )
                    }
                }
                when (turn.state) {
                    TurnState.ERROR -> Text(turn.error ?: "The request failed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TurnState.CANCELLED -> Text("Stopped.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Unit
                }
                if (turn.state != TurnState.STREAMING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (turn.text.isNotBlank()) TextButton(onClick = { onCopy(turn.text) }) { Text("Copy") }
                        if (isLast && !busy) TextButton(onClick = onRegenerate) { Text(if (turn.state == TurnState.ERROR) "Retry" else "Regenerate") }
                        if (turn.state == TurnState.DONE && turn.text.contains("```")) TextButton(onClick = onApply) { Text("Review & apply") }
                    }
                }
            }
        }
    }
}

/** Checkbox list of project files (text files only; secrets and build output are not offered). */
@Composable
fun FilePickerDialog(paths: List<String>, selected: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit, max: Int = 12) {
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateOf(selected.toSet()) }
    val shown = remember(paths, query) { paths.filter { query.isBlank() || it.contains(query, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose files (${chosen.value.size}/$max)") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(shown, key = { it }) { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = p in chosen.value,
                                onCheckedChange = { on -> chosen.value = if (on && chosen.value.size < max) chosen.value + p else if (!on) chosen.value - p else chosen.value },
                            )
                            Text(p, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(paths.filter { it in chosen.value }) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Phase 24: preview and approve generated code. Every file is listed with new/changed, the size of the change, the result of the
 * simple checks and a before/after view. Nothing is written here: accepted files go to the Changes tab, where Apply is a second step.
 */
@Composable
private fun CodeReviewDialog(review: CodeReview, onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    val selected = remember(review.turnId) { mutableStateOf(review.acceptablePaths.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review generated code") },
        text = {
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "Nothing is written yet. Choose the files you accept. They are added to Changes, where you press Apply.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(review.files, key = { it.path }) { f ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ChangeFileRow(
                            f.change, checked = f.path in selected.value, enabled = f.canAccept,
                            onToggle = { on -> selected.value = if (on) selected.value + f.path else selected.value - f.path },
                        )
                        Text(
                            f.verdict.label, style = MaterialTheme.typography.labelSmall,
                            color = if (f.verdict == ReviewVerdict.BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        f.problems.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
                items(review.rejected) { r -> Text("Not used: ${r.path} - ${r.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                items(review.warnings) { w -> Text(w, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.value) }, enabled = selected.value.isNotEmpty()) { Text("Add to Changes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
