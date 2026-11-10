package com.almus.appcreatorpro.preview

import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.ContextPolicy
import com.almus.appcreatorpro.ai.SecretScanner

/** What a finished build of one target looks like. */
sealed interface PreviewBuild {
    val fidelity: PreviewFidelity

    /** HTML/CSS/JS run by a real web engine in the sandbox. */
    data class Web(val bundle: WebBundle) : PreviewBuild { override val fidelity get() = PreviewFidelity.WEB_ENGINE }

    /** An approximation drawn by ALMUS ([tree] is already laid out for the chosen device). */
    data class Wire(val tree: LaidOut?, val warnings: List<String>, val widthDp: Int, val heightDp: Int) : PreviewBuild {
        override val fidelity get() = PreviewFidelity.APPROXIMATE
    }

    data class Unavailable(val reason: String) : PreviewBuild { override val fidelity get() = PreviewFidelity.UNAVAILABLE }
}

class PreviewBuildInput(val target: PreviewTarget, val config: PreviewConfig, val files: AiProjectFiles)

/**
 * One engine per kind of project. A new kind (Flutter, a cloud native render) is added by implementing this and
 * registering it in [PreviewEngines]; nothing else in the preview system has to change.
 */
interface PreviewEngine {
    val kind: PreviewKind
    suspend fun build(input: PreviewBuildInput): PreviewBuild
}

private suspend fun readSafely(files: AiProjectFiles, path: String): String? =
    try { files.read(path) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

class WebPreviewEngine : PreviewEngine {
    override val kind = PreviewKind.WEB

    override suspend fun build(input: PreviewBuildInput): PreviewBuild {
        val all = input.files.list()
        val texts = LinkedHashMap<String, String>()
        for (p in all) {
            if (texts.size >= WebPreviewBuilder.MAX_FILES) break
            if (!WebPreviewBuilder.isServable(p)) continue
            readSafely(input.files, p)?.let { texts[p] = it }
        }
        return PreviewBuild.Web(WebPreviewBuilder.build(input.target.path, texts, all.toSet(), input.config.dark, input.config.allowInternet))
    }
}

/** Reads the resource files and layouts both Android engines need. */
internal suspend fun androidResources(files: AiProjectFiles, dark: Boolean, warnings: MutableList<String>): Pair<ResourceTable, Map<String, LayoutFile>> {
    val all = files.list().filter { !ContextPolicy.isHiddenPath(it) && !SecretScanner.isSensitivePath(it) }
    val values = LinkedHashMap<String, String>()
    for (p in all.filter { PreviewDetector.isValues(it) }.take(40)) readSafely(files, p)?.let { values[p] = it }
    val resources = ResourceTable.fromFiles(values, dark, warnings)
    val layouts = LinkedHashMap<String, LayoutFile>()
    val layoutPaths = all.filter { PreviewDetector.isLayout(it) }.sortedBy { !it.contains("/res/layout/") }
    for (p in layoutPaths.take(80)) {
        val name = p.substringAfterLast('/').removeSuffix(".xml")
        if (name in layouts) continue
        readSafely(files, p)?.let { layouts[name] = LayoutFile(p, it) }
    }
    return resources to layouts
}

class XmlPreviewEngine : PreviewEngine {
    override val kind = PreviewKind.ANDROID_XML

    override suspend fun build(input: PreviewBuildInput): PreviewBuild {
        val warnings = ArrayList<String>()
        val (resources, layouts) = androidResources(input.files, input.config.dark, warnings)
        val text = readSafely(input.files, input.target.path) ?: return PreviewBuild.Unavailable("${input.target.path} could not be read.")
        val built = XmlLayoutBuilder(resources, layouts, PreviewPalette.of(input.config.dark)).build(LayoutFile(input.target.path, text))
        warnings += built.warnings
        val w = input.config.widthDp
        val h = input.config.contentHeightDp
        val tree = built.root?.let { LayoutEngine.layout(it, w.toFloat(), h.toFloat(), input.config.fontScale) }
        return PreviewBuild.Wire(tree, warnings.take(80), w, h)
    }
}

class ComposePreviewEngine : PreviewEngine {
    override val kind = PreviewKind.ANDROID_COMPOSE

    override suspend fun build(input: PreviewBuildInput): PreviewBuild {
        val function = input.target.function ?: return PreviewBuild.Unavailable("No @Composable function was chosen.")
        val warnings = ArrayList<String>()
        val (resources, _) = androidResources(input.files, input.config.dark, warnings)
        val sources = LinkedHashMap<String, String>()
        for (p in input.files.list()) {
            if (sources.size >= 60) break
            if (!p.endsWith(".kt") || ContextPolicy.isHiddenPath(p)) continue
            val t = readSafely(input.files, p) ?: continue
            if (t.contains("@Composable")) sources[p] = t
        }
        val built = ComposeOutlineBuilder(resources, PreviewPalette.of(input.config.dark), sources).build(input.target.path, function)
        warnings += built.warnings
        val w = input.config.widthDp
        val h = input.config.contentHeightDp
        val tree = built.root?.let { LayoutEngine.layout(it, w.toFloat(), h.toFloat(), input.config.fontScale) }
        return PreviewBuild.Wire(tree, warnings.take(80), w, h)
    }
}

/**
 * Flutter is prepared for, not supported. Detection exists ([PreviewDetector]) so the project shows up in the target
 * list with an honest message. The intended routes, once a Flutter toolchain is available in a cloud job: a Flutter web
 * build served through the SAME sandbox as [WebPreviewEngine] (a [PreviewBuild.Web] bundle), or golden screenshots
 * rendered by `flutter test` shown as images. Neither exists yet.
 */
class FlutterPreviewEngine : PreviewEngine {
    override val kind = PreviewKind.FLUTTER
    override suspend fun build(input: PreviewBuildInput): PreviewBuild =
        PreviewBuild.Unavailable("Flutter previews are not available yet. ALMUS cannot run the Flutter toolchain on the phone; a cloud build that produces a Flutter web bundle is the planned route.")
}

class PreviewEngines(engines: List<PreviewEngine>) {
    private val byKind = engines.associateBy { it.kind }
    fun forKind(kind: PreviewKind): PreviewEngine? = byKind[kind]

    companion object {
        fun default() = PreviewEngines(listOf(WebPreviewEngine(), XmlPreviewEngine(), ComposePreviewEngine(), FlutterPreviewEngine()))
    }
}
