package com.almus.appcreatorpro.preview

import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiJson
import com.almus.appcreatorpro.ai.AiMessage
import com.almus.appcreatorpro.ai.AiRequest
import com.almus.appcreatorpro.ai.AiRole
import com.almus.appcreatorpro.ai.AiStep
import com.almus.appcreatorpro.ai.AiTaskKind
import com.almus.appcreatorpro.ai.AiErrorKind
import com.almus.appcreatorpro.ai.AiException
import com.almus.appcreatorpro.ai.AiStreamEvent
import com.almus.appcreatorpro.ai.GeneratedFileValidator
import com.almus.appcreatorpro.ai.Parsed
import com.almus.appcreatorpro.ai.ProjectContextBuilder
import com.almus.appcreatorpro.ai.SecretScanner
import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.delay

/** Which files the AI may produce for each preview target. Anything else in the answer is refused and reported. */
object UiPathRules {
    const val MAX_FILES = 8
    const val MAX_FILE_CHARS = 128 * 1024
    private val webExt = setOf("html", "htm", "css", "js", "mjs", "svg", "json", "txt")
    private val androidRes = Regex("^app/src/main/res/(layout|layout-[a-z0-9+-]+|values|values-night|drawable|color|menu)/[a-z0-9_]+\\.xml$")
    private val androidKt = Regex("^app/src/main/(java|kotlin)/[A-Za-z0-9_/]+\\.kt$")

    /** Null = allowed. */
    fun check(kind: PreviewKind, path: String): String? {
        GeneratedFileValidator.checkPath(path)?.let { return it }
        return when (kind) {
            PreviewKind.WEB -> if (path.substringAfterLast('.', "").lowercase() in webExt && path.count { it == '/' } <= 4) null else "Only plain web files (html, css, js, svg, json) are allowed for a web preview."
            PreviewKind.ANDROID_XML -> if (androidRes.matches(path)) null else "Android XML files must be under app/src/main/res/ (layout, values, drawable, color or menu) with lowercase names."
            PreviewKind.ANDROID_COMPOSE -> if (androidKt.matches(path) || androidRes.matches(path)) null else "Compose files must be Kotlin files under app/src/main/java or kotlin (or resources under res/)."
            PreviewKind.FLUTTER -> "Flutter previews are not available, so no Flutter files are accepted."
        }
    }
}

/** Notes about generated code that is not blocked (it only runs in the sandbox) but that the user should know about. */
object UiHazards {
    private val kotlinApis = listOf("Runtime.getRuntime", "ProcessBuilder", "DexClassLoader", "System.exit", "Class.forName", "setAccessible(true)")

    fun scan(path: String, content: String): List<String> {
        val out = ArrayList<String>()
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext == "html" || ext == "htm" || ext == "css" || ext == "js" || ext == "svg") {
            if (Regex("(?i)(src|href|url\\()\\s*=?\\s*[\"']?\\s*(https?:)?//").containsMatchIn(content)) out += "$path loads something from the internet. Previews have no network access, so it will not appear there."
        }
        if (ext == "kt") for (api in kotlinApis) if (content.contains(api)) out += "$path uses $api, which is unusual for UI code. Review it before saving it to your project."
        return out
    }
}

object UiDraftParser {
    fun parse(text: String, truncated: Boolean, kind: PreviewKind): Parsed<UiDraft> {
        val obj = when (val p = AiJson.parseObject(text, truncated)) { is Parsed.Ok -> p.value; is Parsed.Bad -> return Parsed.Bad(p.reason) }
        val raw = obj["files"] as? List<*> ?: return Parsed.Bad("The answer has no \"files\" list.")
        val files = LinkedHashMap<String, String>()
        val rejected = ArrayList<String>()
        val warnings = ArrayList<String>()
        for (item in raw) {
            val m = item as? Map<*, *> ?: continue
            val path = (m["path"] as? String)?.trim() ?: continue
            val content = m["content"] as? String
            when {
                files.size >= UiPathRules.MAX_FILES -> rejected += "$path: too many files (limit ${UiPathRules.MAX_FILES})."
                content == null || content.isBlank() -> rejected += "$path: empty."
                path.lowercase() in files.keys.map { it.lowercase() } -> rejected += "$path: appears twice."
                content.length > UiPathRules.MAX_FILE_CHARS -> rejected += "$path: too large (limit ${UiPathRules.MAX_FILE_CHARS / 1024} KB)."
                content.indexOf('\u0000') >= 0 -> rejected += "$path: contains binary data."
                SecretScanner.containsSecret(content) -> rejected += "$path: contains what looks like a secret."
                else -> {
                    val why = UiPathRules.check(kind, path)
                    if (why != null) rejected += "$path: $why" else { files[path] = content; warnings += UiHazards.scan(path, content) }
                }
            }
        }
        if (files.isEmpty()) return Parsed.Bad("No usable files in the answer." + if (rejected.isNotEmpty()) " " + rejected.first() else "")
        return Parsed.Ok(UiDraft(files, (obj["summary"] as? String).orEmpty().trim().take(400), AiJson.strings(obj["notes"], 8, 300), rejected, warnings.distinct()))
    }
}

sealed interface UiGenResult {
    data class Ok(val draft: UiDraft) : UiGenResult
    data class Failed(val message: String, val retryable: Boolean) : UiGenResult
}

/**
 * Asks the AI (through the same [AiBackend] as everything else, so any provider works) for UI code for one preview
 * target. The result is a [UiDraft]: it is shown in the preview but not written to the project. Saving it goes through
 * the change manager, with the user's approval.
 */
class PreviewUiGenerator(private val backend: AiBackend, private val waitMs: suspend (Long) -> Unit = { delay(it) }) {

    suspend fun generate(
        kind: PreviewKind,
        request: String,
        files: AiProjectFiles,
        focusPaths: List<String>,
        current: UiDraft?,
        projectName: String?,
        projectType: ProjectType?,
    ): UiGenResult {
        if (kind == PreviewKind.FLUTTER) return UiGenResult.Failed("Flutter previews are not available, so UI generation for Flutter is not offered yet.", false)
        val text = request.trim()
        if (text.length < 8) return UiGenResult.Failed("Describe the screen in a little more detail.", false)
        if (text.length > 3000) return UiGenResult.Failed("The description is too long (limit 3000 characters).", false)
        val bundle = ProjectContextBuilder(files).build(projectName, projectType, focusPaths, includeStructure = true)
        val prompt = buildString {
            append("TARGET: ").append(when (kind) { PreviewKind.WEB -> "web"; PreviewKind.ANDROID_XML -> "android_xml"; PreviewKind.ANDROID_COMPOSE -> "android_compose"; PreviewKind.FLUTTER -> "flutter" }).append("\n\n")
            append("REQUEST:\n").append(text).append("\n\n")
            if (current != null) {
                append("CURRENT DRAFT (change it according to the request and return ALL files again):\n")
                for ((p, c) in current.files) append("=== ").append(p).append(" ===\n").append(SecretScanner.redact(c).text.take(20_000)).append("\n")
                append('\n')
            }
            append(bundle.render(includeStructure = true))
        }
        val messages = mutableListOf(AiMessage(AiRole.USER, prompt))
        var parseRetries = 0
        var transport = 0
        while (true) {
            val completion = try {
                val sb = StringBuilder()
                var finish: String? = null
                var finished = false
                backend.stream(AiRequest(AiTaskKind.PREVIEW_UI, messages)).collect { ev ->
                    when (ev) {
                        is AiStreamEvent.Started -> {}
                        is AiStreamEvent.Delta -> sb.append(ev.text)
                        is AiStreamEvent.Finished -> { finish = ev.finishReason; finished = true }
                    }
                }
                if (!finished) throw AiException(AiErrorKind.NETWORK, "The connection ended before the answer was complete.", retryable = true)
                sb.toString() to (finish == "MAX_TOKENS")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AiException) {
                if (e.retryable && transport < 2) { transport++; waitMs(1000L shl (transport - 1)); continue }
                return UiGenResult.Failed(e.message.orEmpty(), e.retryable)
            } catch (e: Exception) {
                return UiGenResult.Failed("The AI answer could not be processed (${e.javaClass.simpleName}).", true)
            }
            when (val p = UiDraftParser.parse(completion.first, completion.second, kind)) {
                is Parsed.Ok -> return UiGenResult.Ok(p.value)
                is Parsed.Bad -> {
                    if (parseRetries >= 1) return UiGenResult.Failed(p.reason, true)
                    parseRetries++
                    messages += AiMessage(AiRole.MODEL, completion.first.take(1500).ifBlank { "(empty answer)" })
                    messages += AiMessage(AiRole.USER, if (completion.second) "Your answer was cut off. Answer again with ONE JSON object and fewer, smaller files." else "Your answer could not be used: ${p.reason.take(200)} Answer again with ONE valid JSON object.")
                }
            }
        }
    }
}
