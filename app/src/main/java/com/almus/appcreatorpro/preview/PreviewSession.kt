package com.almus.appcreatorpro.preview

import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PreviewStatus { IDLE, LOADING, READY, EMPTY, ERROR }

/** UI the AI generated, shown over the project. It exists only in memory until the user saves it. */
data class UiDraft(val files: Map<String, String>, val summary: String, val notes: List<String>, val rejected: List<String> = emptyList(), val warnings: List<String> = emptyList())

data class PreviewState(
    val targets: List<PreviewTarget> = emptyList(),
    val selected: PreviewTarget? = null,
    val config: PreviewConfig = PreviewConfig(),
    val build: PreviewBuild? = null,
    val status: PreviewStatus = PreviewStatus.IDLE,
    val error: String? = null,
    /** Raised after every successful build; the web view reloads when it changes. */
    val version: Int = 0,
    val console: List<ConsoleEntry> = emptyList(),
    val inspect: Boolean = false,
    val pick: PickEvent? = null,
    val draft: UiDraft? = null,
    val nativeReadiness: NativeRenderReadiness? = null,
    val lastBuiltAtMillis: Long = 0L,
)

/**
 * The state holder of one project's preview. It reads project files through [PreviewFiles] (read-only), builds with the
 * engine for the selected target, keeps the console, and refreshes on request or, while watching, when files change.
 */
class PreviewSession(
    private val base: AiProjectFiles,
    private val projectType: ProjectType?,
    private val scope: CoroutineScope,
    private val engines: PreviewEngines = PreviewEngines.default(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val pollMs: Long = 1500L,
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()
    private var buildJob: Job? = null
    private var watchJob: Job? = null
    private var buildCounter = 0

    private fun view(): AiProjectFiles = PreviewFiles(base, _state.value.draft?.files.orEmpty())

    private fun log(level: ConsoleLevel, origin: ConsoleOrigin, msg: String, path: String? = null, line: Int? = null) {
        val e = PreviewConsoleLog.system(clockMillis(), level, origin, msg, path, line)
        _state.update { it.copy(console = PreviewConsoleLog.append(it.console, e)) }
    }

    // ---- building

    /** Detects what can be previewed and builds the default (or the previous) target. */
    fun open() = rebuild(redetect = true, reason = "Opened")

    /** Re-reads the files and rebuilds. */
    fun refresh() = rebuild(redetect = true, reason = "Refreshed")

    fun select(target: PreviewTarget) {
        _state.update { it.copy(selected = target, pick = null) }
        rebuild(redetect = false, reason = "Switched to ${target.label}")
    }

    fun setConfig(change: (PreviewConfig) -> PreviewConfig) {
        val before = _state.value.config
        val after = change(before)
        if (after == before) return
        _state.update { it.copy(config = after) }
        val kind = _state.value.selected?.kind
        // Web previews only reload for what the page can see; sizes and zoom are applied by the view.
        val needsRebuild = after.dark != before.dark || after.allowInternet != before.allowInternet || (kind != PreviewKind.WEB && (after.device != before.device || after.landscape != before.landscape || after.fontScale != before.fontScale || after.showFrame != before.showFrame))
        if (needsRebuild) rebuild(redetect = false, reason = "Settings changed")
        if (after.autoRefresh != before.autoRefresh && watchJob?.isActive == true) { stopWatching(); if (after.autoRefresh) startWatching() }
    }

    private fun rebuild(redetect: Boolean, reason: String) {
        buildJob?.cancel()
        val myBuild = ++buildCounter
        _state.update { it.copy(status = PreviewStatus.LOADING, error = null) }
        buildJob = scope.launch {
            try {
                val files = view()
                var targets = _state.value.targets
                if (redetect || targets.isEmpty()) {
                    val all = files.list()
                    val composable = LinkedHashMap<String, String>()
                    for (p in all) {
                        if (composable.size >= 60) break
                        if (!p.endsWith(".kt")) continue
                        val t = try { files.read(p) } catch (e: CancellationException) { throw e } catch (e: Exception) { continue }
                        if (t.contains("@Composable")) composable[p] = t
                    }
                    targets = PreviewDetector.detect(all, composable)
                }
                var selected = _state.value.selected?.takeIf { s -> targets.any { it.id == s.id } }
                val draftPaths = _state.value.draft?.files?.keys.orEmpty()
                if (selected == null && draftPaths.isNotEmpty()) selected = targets.firstOrNull { it.path in draftPaths }
                if (selected == null) selected = PreviewDetector.defaultTarget(targets, projectType)
                if (selected == null) {
                    if (myBuild == buildCounter) _state.update { it.copy(targets = targets, selected = null, build = null, status = PreviewStatus.EMPTY, error = null) }
                    return@launch
                }
                val engine = engines.forKind(selected.kind)
                val build = engine?.build(PreviewBuildInput(selected, _state.value.config, files)) ?: PreviewBuild.Unavailable("No preview engine for ${selected.kind.label}.")
                val readiness = if (selected.kind == PreviewKind.ANDROID_XML || selected.kind == PreviewKind.ANDROID_COMPOSE) nativeReadiness(files) else null
                if (myBuild != buildCounter) return@launch
                _state.update { it.copy(targets = targets, selected = selected, build = build, status = if (build is PreviewBuild.Unavailable) PreviewStatus.ERROR else PreviewStatus.READY, error = (build as? PreviewBuild.Unavailable)?.reason, version = it.version + 1, nativeReadiness = readiness, lastBuiltAtMillis = clockMillis()) }
                log(ConsoleLevel.DEBUG, ConsoleOrigin.SYSTEM, "$reason: ${selected.label} (${build.fidelity.label})")
                when (build) {
                    is PreviewBuild.Web -> build.bundle.warnings.forEach { log(ConsoleLevel.WARN, ConsoleOrigin.SYSTEM, it) }
                    is PreviewBuild.Wire -> build.warnings.forEach { log(ConsoleLevel.WARN, ConsoleOrigin.PARSE, it) }
                    is PreviewBuild.Unavailable -> log(ConsoleLevel.ERROR, ConsoleOrigin.SYSTEM, build.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (myBuild == buildCounter) {
                    val msg = "The preview could not be built (${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)})."
                    _state.update { it.copy(status = PreviewStatus.ERROR, error = msg) }
                    log(ConsoleLevel.ERROR, ConsoleOrigin.SYSTEM, msg)
                }
            }
        }
    }

    private suspend fun nativeReadiness(files: AiProjectFiles): NativeRenderReadiness {
        val all = files.list()
        val texts = LinkedHashMap<String, String>()
        for (p in all) {
            if (texts.size >= 80) break
            val isGradle = p.endsWith(".gradle") || p.endsWith(".gradle.kts") || p.endsWith(".toml") || p.endsWith("gradle.properties")
            val isKt = p.endsWith(".kt") && !p.contains("/build/")
            if (!isGradle && !isKt) continue
            try { texts[p] = files.read(p) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* skipped */ }
        }
        return NativeRenderReadinessChecker.check(texts, all.toSet())
    }

    // ---- auto refresh

    /** While watching, a change to a previewable file triggers a refresh (polling, so it also sees edits made elsewhere). */
    fun startWatching() {
        if (watchJob?.isActive == true || !_state.value.config.autoRefresh) return
        watchJob = scope.launch {
            var last = PreviewDetector.fingerprint(view())
            while (isActive) {
                waitMs(pollMs)
                if (!_state.value.config.autoRefresh) continue
                val now = try { PreviewDetector.fingerprint(view()) } catch (e: CancellationException) { throw e } catch (e: Exception) { last }
                if (now != last) { last = now; rebuild(redetect = true, reason = "Files changed") }
            }
        }
    }

    fun stopWatching() { watchJob?.cancel(); watchJob = null }

    fun close() { stopWatching(); buildJob?.cancel() }

    // ---- console and inspection (fed by the sandboxed view)

    fun onWebConsole(webViewLevel: String?, message: String?, sourceId: String?, line: Int) {
        val text = message.orEmpty()
        PreviewConsoleLog.parsePick(text)?.let { pick -> _state.update { it.copy(pick = pick) }; return }
        val entry = PreviewConsoleLog.fromWebView(clockMillis(), webViewLevel, text, sourceId, line, _state.value.selected?.path ?: "index.html")
        _state.update { it.copy(console = PreviewConsoleLog.append(it.console, entry)) }
    }

    fun onBlocked(reason: String) = log(ConsoleLevel.WARN, ConsoleOrigin.BLOCKED, reason)

    fun onResourceError(description: String, url: String?) {
        val path = url?.let { PreviewRequestPolicy.pathOf(it, _state.value.selected?.path ?: "index.html") }
        log(ConsoleLevel.ERROR, ConsoleOrigin.RESOURCE, "Could not load ${path ?: url.orEmpty().take(100)}: $description", path)
    }

    fun onRenderProcessGone(crashed: Boolean) {
        log(ConsoleLevel.ERROR, ConsoleOrigin.SYSTEM, if (crashed) "The preview's web process crashed. Refresh to start it again." else "The preview's web process was stopped by the system. Refresh to start it again.")
        _state.update { it.copy(status = PreviewStatus.ERROR, error = "The preview process stopped. Tap refresh.") }
    }

    fun onRenderUnresponsive() = log(ConsoleLevel.ERROR, ConsoleOrigin.SYSTEM, "The page stopped responding (an endless loop?). It was stopped; refresh to start again.")

    fun clearConsole() { _state.update { it.copy(console = emptyList()) } }

    fun setInspect(on: Boolean) { _state.update { it.copy(inspect = on, pick = if (on) it.pick else null) } }

    fun consumePick() { _state.update { it.copy(pick = null) } }

    /** Tap on a drawn view (approximate previews). Returns the source position, if the node has one. */
    fun pickAt(x: Float, y: Float): PickEvent? {
        val b = _state.value.build as? PreviewBuild.Wire ?: return null
        val hit = b.tree?.hit(x, y) ?: return null
        val path = hit.node.sourcePath ?: return null
        val line = hit.node.line ?: return null
        val pick = PickEvent(path, line, hit.node.tag)
        _state.update { it.copy(pick = pick) }
        return pick
    }

    // ---- AI draft

    fun applyDraft(draft: UiDraft) {
        _state.update { it.copy(draft = draft, selected = null) }
        rebuild(redetect = true, reason = "AI draft shown")
    }

    fun discardDraft() {
        if (_state.value.draft == null) return
        _state.update { it.copy(draft = null, selected = null) }
        rebuild(redetect = true, reason = "AI draft discarded")
    }

    /** After the draft was saved to the project: the files are real now, so the overlay is dropped. */
    fun draftSaved() {
        _state.update { it.copy(draft = null) }
        rebuild(redetect = true, reason = "Draft saved to the project")
    }
}
