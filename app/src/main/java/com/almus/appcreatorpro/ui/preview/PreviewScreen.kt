package com.almus.appcreatorpro.ui.preview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.preview.ConsoleEntry
import com.almus.appcreatorpro.preview.ConsoleLevel
import com.almus.appcreatorpro.preview.DeviceCatalog
import com.almus.appcreatorpro.preview.PreviewBuild
import com.almus.appcreatorpro.preview.PreviewConsoleLog
import com.almus.appcreatorpro.preview.PreviewKind
import com.almus.appcreatorpro.preview.PreviewPalette
import com.almus.appcreatorpro.preview.PreviewSession
import com.almus.appcreatorpro.preview.PreviewState
import com.almus.appcreatorpro.preview.PreviewStatus
import com.almus.appcreatorpro.preview.NativeRenderAvailability
import com.almus.appcreatorpro.preview.UnavailableNativeRender
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.ConfirmDialog

/** Live preview: pick a target and a device, see it, read the console, ask the AI for UI, and see what native options exist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(projectId: String, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenEditorAt: (String, Int) -> Unit) {
    val vm: PreviewViewModel = viewModel(key = "preview-$projectId", factory = AppViewModelProvider.previewFactory(projectId))
    val session by vm.session.collectAsStateWithLifecycle()
    val availability by vm.availability.collectAsStateWithLifecycle()
    val aiBusy by vm.aiBusy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live preview") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { session?.refresh() }) { Text("Refresh") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = session
        if (s == null) {
            Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val st by s.state.collectAsStateWithLifecycle()
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Preview", "Console${if (st.console.isNotEmpty()) " (${st.console.size})" else ""}", "AI", "Native").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1) })
                }
            }
            when (tab) {
                0 -> PreviewTab(s, st, onOpenEditorAt)
                1 -> ConsoleTab(s, st, onOpenEditorAt)
                2 -> AiTab(st, availability, aiBusy, onOpenSettings, vm::refreshAvailability, vm::generate, vm::saveDraft, vm::discardDraft)
                else -> NativeTab(st)
            }
        }
    }
}

@Composable
private fun <T> Dropdown(label: String, current: String, options: List<Pair<String, T>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $current", maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) }) }
        }
    }
}

@Composable
private fun PreviewTab(s: PreviewSession, st: PreviewState, onOpenEditorAt: (String, Int) -> Unit) {
    val cfg = st.config
    val build = st.build
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (st.targets.isEmpty() && st.status != PreviewStatus.LOADING) {
            Text("Nothing to preview yet. A preview needs an HTML page, an Android XML layout (res/layout) or a @Composable function in the project.", style = MaterialTheme.typography.bodyMedium)
        }
        Dropdown("Preview", st.selected?.label ?: "none", st.targets.map { "${it.kind.label}: ${it.label}" to it }, { s.select(it) })

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Dropdown("Device", cfg.device.name, DeviceCatalog.all.map { "${it.name} (${it.widthDp}x${it.heightDp})" to it }, { d -> s.setConfig { it.withDevice(d) } }, Modifier.weight(1f))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !cfg.dark, onClick = { s.setConfig { it.copy(dark = false) } }, label = { Text("Light") })
            FilterChip(selected = cfg.dark, onClick = { s.setConfig { it.copy(dark = true) } }, label = { Text("Dark") })
            FilterChip(selected = cfg.landscape, onClick = { s.setConfig { it.copy(landscape = !it.landscape) } }, label = { Text("Landscape") })
            FilterChip(selected = cfg.showFrame, onClick = { s.setConfig { it.copy(showFrame = !it.showFrame) } }, label = { Text("Frame") })
            listOf<Pair<String, Int?>>("Fit" to null, "50%" to 50, "75%" to 75, "100%" to 100).forEach { (t, z) ->
                FilterChip(selected = cfg.zoomPercent == z, onClick = { s.setConfig { it.withZoom(z) } }, label = { Text(t) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1.0f, 1.15f, 1.3f, 1.5f).forEach { f -> FilterChip(selected = cfg.fontScale == f, onClick = { s.setConfig { it.withFontScale(f) } }, label = { Text("Text ${(f * 100).toInt()}%") }) }
        }
        // Responsive: free width, plus the breakpoints found in the project's CSS.
        Text("Width: ${cfg.widthDp} dp", style = MaterialTheme.typography.labelMedium)
        Slider(value = cfg.widthDp.toFloat(), onValueChange = { v -> s.setConfig { it.withCustomSize(v.toInt(), cfg.heightDp) } }, valueRange = DeviceCatalog.MIN_DP.toFloat()..1400f)
        val bps = (build as? PreviewBuild.Web)?.bundle?.breakpoints.orEmpty()
        if (bps.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CSS breakpoints:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                bps.forEach { bp -> FilterChip(selected = cfg.widthDp == bp, onClick = { s.setConfig { it.withCustomSize(bp, cfg.heightDp) } }, label = { Text("$bp") }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = cfg.autoRefresh, onCheckedChange = { on -> s.setConfig { it.copy(autoRefresh = on) } })
            Text("Refresh when files change", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        if (st.selected?.kind == PreviewKind.WEB) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = st.inspect, onCheckedChange = { s.setInspect(it) })
                Text("Tap an element to find its source", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = cfg.allowInternet, onCheckedChange = { on -> s.setConfig { it.copy(allowInternet = on) } })
                Text("Allow internet for this preview (off = fully offline and safest)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }

        // Honest labelling, always visible.
        val fidelity = build?.fidelity ?: st.selected?.kind?.fidelity
        if (fidelity != null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (fidelity.name == "APPROXIMATE" || fidelity.name == "UNAVAILABLE") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(10.dp)) {
                    Text(fidelity.label, style = MaterialTheme.typography.titleSmall)
                    Text(fidelity.note, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        st.draft?.let { d ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("Showing an AI draft (${d.files.size} file(s)). It is not saved in your project. Open the AI tab to save or discard it.", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (st.status == PreviewStatus.LOADING) CircularProgressIndicator()

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val frameExtra = if (cfg.showFrame) 20f else 0f
            val available = maxWidth.value
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (build) {
                    is PreviewBuild.Web -> FitToWidth(cfg.widthDp + frameExtra, cfg.heightDp + frameExtra, available, cfg.zoomPercent) {
                        DeviceFrame(cfg) { SandboxedWebView(build.bundle, st.version, cfg, st.inspect, s, Modifier.fillMaxSize()) }
                    }
                    is PreviewBuild.Wire -> {
                        val tree = build.tree
                        if (tree == null) Text("Nothing could be drawn. See the console for the reasons.", style = MaterialTheme.typography.bodyMedium)
                        else FitToWidth(cfg.widthDp + frameExtra, cfg.heightDp + frameExtra, available, cfg.zoomPercent) {
                            DeviceFrame(cfg) { WireScreen(tree, PreviewPalette.of(cfg.dark), st.pick, cfg.fontScale, build.widthDp, build.heightDp) { x, y -> s.pickAt(x, y) } }
                        }
                    }
                    is PreviewBuild.Unavailable, null -> {}
                }
            }
        }
        st.pick?.let { p ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("<${p.tag}> at ${p.path}:${p.line}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onOpenEditorAt(p.path, p.line); s.consumePick() }) { Text("Open in editor") }
                }
            }
        }
        val warnings = (build as? PreviewBuild.Wire)?.warnings.orEmpty()
        if (warnings.isNotEmpty()) Text("${warnings.size} note(s) about what the approximation could not draw are in the Console tab.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConsoleTab(s: PreviewSession, st: PreviewState, onOpenEditorAt: (String, Int) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var min by remember { mutableStateOf(ConsoleLevel.DEBUG) }
    val shown = PreviewConsoleLog.filter(st.console, min)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("All" to ConsoleLevel.DEBUG, "Logs" to ConsoleLevel.LOG, "Warnings" to ConsoleLevel.WARN, "Errors" to ConsoleLevel.ERROR).forEach { (t, l) ->
                FilterChip(selected = min == l, onClick = { min = l }, label = { Text(t) })
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(PreviewConsoleLog.asText(shown))) }, enabled = shown.isNotEmpty()) { Text("Copy") }
            TextButton(onClick = { s.clearConsole() }, enabled = st.console.isNotEmpty()) { Text("Clear") }
        }
        if (shown.isEmpty()) Text("Nothing here. Page output, script errors, blocked requests and notes about the preview appear in this console.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown.asReversed()) { e -> ConsoleRow(e, onOpenEditorAt) }
        }
    }
}

@Composable
private fun ConsoleRow(e: ConsoleEntry, onOpenEditorAt: (String, Int) -> Unit) {
    val color = when (e.level) {
        ConsoleLevel.ERROR -> MaterialTheme.colorScheme.error
        ConsoleLevel.WARN -> androidx.compose.ui.graphics.Color(0xFFB26A00)
        ConsoleLevel.DEBUG -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurface
    }
    val jump = e.path != null && e.line != null
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).then(if (jump) Modifier.clickable { onOpenEditorAt(e.path!!, e.line!!) } else Modifier)) {
        Text("[${e.origin.label}] ${e.message}${if (e.repeat > 1) "  x${e.repeat}" else ""}", color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        if (jump) Text("${e.path}:${e.line}  (tap to open)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AiTab(
    st: PreviewState,
    availability: AiAvailability?,
    busy: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onGenerate: (PreviewKind, String, Boolean) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    var request by rememberSaveable { mutableStateOf("") }
    var confirmSave by remember { mutableStateOf(false) }
    val notReady = availability as? AiAvailability.NotReady
    val kinds = listOf(PreviewKind.WEB, PreviewKind.ANDROID_XML, PreviewKind.ANDROID_COMPOSE)
    var kind by rememberSaveable { mutableStateOf(st.selected?.kind?.takeIf { it in kinds } ?: PreviewKind.WEB) }
    val draft = st.draft
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notReady?.let {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(it.reason)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onOpenSettings) { Text("Set up AI") }; TextButton(onClick = onRefresh) { Text("Check again") } }
                }
            }
        }
        Text("Describe a screen. The AI writes the UI code and it appears in the preview as a draft. Nothing is written to your project until you save it.", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            kinds.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
        }
        if (kind != PreviewKind.WEB) Text("The preview of Android code is an approximation drawn by ALMUS, not the Android runtime.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        OutlinedTextField(request, { request = it }, label = { Text(if (draft == null) "What should the screen look like?" else "What should change?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onGenerate(kind, request, false) }, enabled = !busy && notReady == null && request.isNotBlank()) { Text(if (draft == null) "Generate" else "Generate again") }
            if (draft != null) OutlinedButton(onClick = { onGenerate(kind, request, true); request = "" }, enabled = !busy && notReady == null && request.isNotBlank()) { Text("Refine draft") }
            if (busy) CircularProgressIndicator(Modifier.padding(4.dp))
        }
        if (draft != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Draft", style = MaterialTheme.typography.titleSmall)
                    if (draft.summary.isNotBlank()) Text(draft.summary, style = MaterialTheme.typography.bodyMedium)
                    draft.files.keys.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                    draft.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    draft.rejected.forEach { Text("Refused: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    draft.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirmSave = true }) { Text("Save to project") }
                OutlinedButton(onClick = onDiscard) { Text("Discard draft") }
            }
        }
    }
    if (confirmSave && draft != null) {
        ConfirmDialog(
            title = "Save ${draft.files.size} file(s) to your project?",
            message = "Files with the same path are replaced: ${draft.files.keys.take(6).joinToString()}. This goes through the AI change history, so you can undo it there.",
            confirmLabel = "Save",
            onConfirm = { confirmSave = false; onSave() },
            onDismiss = { confirmSave = false },
            destructive = true,
        )
    }
}

@Composable
private fun NativeTab(st: PreviewState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("What this preview is, and what it is not", style = MaterialTheme.typography.titleMedium)
        Text("HTML, CSS and JavaScript run in a real web engine (Android System WebView), sandboxed from your files and the network.", style = MaterialTheme.typography.bodyMedium)
        Text("Android XML layouts and Jetpack Compose are NOT run. ALMUS reads the source and draws an approximation (boxes, text, simple widgets). A WebView cannot show them, and this drawing is not the Android runtime: real fonts, themes, Material components, animations and many attributes differ.", style = MaterialTheme.typography.bodyMedium)
        Text("Native-fidelity options", style = MaterialTheme.typography.titleSmall)
        Text("1. Cloud render with Layoutlib (the engine of Android Studio's preview), through Paparazzi or Google's Compose Preview Screenshot Testing: a cloud job renders @Preview functions and layouts to images.", style = MaterialTheme.typography.bodySmall)
        Text("2. Run the built app on an emulator in the cloud and take screenshots: real runtime, slow.", style = MaterialTheme.typography.bodySmall)
        Text("3. Install the built APK on this device from the Cloud build screen: the real thing.", style = MaterialTheme.typography.bodySmall)
        val avail = UnavailableNativeRender.availability()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(UnavailableNativeRender.name, style = MaterialTheme.typography.titleSmall)
                Text((avail as? NativeRenderAvailability.Unavailable)?.reason ?: "Available", style = MaterialTheme.typography.bodySmall)
            }
        }
        val r = st.nativeReadiness
        if (r != null) {
            Text("What your project still needs for a cloud render", style = MaterialTheme.typography.titleSmall)
            r.items.forEach { Text("${if (it.ok) "[ok]" else "[missing]"} ${it.label}: ${it.detail}", style = MaterialTheme.typography.bodySmall) }
        } else Text("Select an Android layout or Compose preview to see what a cloud render would need.", style = MaterialTheme.typography.bodySmall)
        Text("Flutter", style = MaterialTheme.typography.titleSmall)
        Text("Flutter projects are detected but cannot be previewed yet. The planned route is a cloud build that produces a Flutter web bundle, which would open in the same sandbox as web pages.", style = MaterialTheme.typography.bodySmall)
    }
}
