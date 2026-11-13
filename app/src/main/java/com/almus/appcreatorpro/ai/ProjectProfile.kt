package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.domain.model.ProjectType

/**
 * Phase 24: what the coding assistant knows about the project BEFORE it writes code: language, UI toolkit, architecture,
 * dependencies and coding conventions. It is detected from the project's own files, on the phone, and only these derived
 * facts (never file text) are put into a request. Plain Kotlin (no Android classes), so it is unit-tested on a normal JVM.
 */
enum class UiToolkit(val label: String) {
    COMPOSE("Jetpack Compose"),
    XML_VIEWS("Android XML layouts (Views)"),
    COMPOSE_AND_XML("Jetpack Compose together with XML layouts"),
    WEB("HTML / CSS / JavaScript"),
    UNKNOWN("not detected"),
}

data class CodingConventions(
    /** "4 spaces", "2 spaces" or "tabs"; null when the sample was too small to tell. */
    val indent: String?,
    /** Kotlin: are trailing commas used in multi-line argument lists? null = unknown. */
    val trailingCommas: Boolean?,
    /** 100 or 120 when the code stays inside that width; null otherwise. */
    val maxLineLength: Int?,
    val notes: List<String>,
)

data class ProjectProfile(
    val projectName: String?,
    val language: String,
    val uiToolkit: UiToolkit,
    val packageName: String?,
    val sdk: String?,
    val architecture: List<String>,
    /** Folders directly under the base package, e.g. ui, data, domain. */
    val layers: List<String>,
    /** Well-known libraries found in the build files or imports, by name. */
    val libraries: List<String>,
    /** Raw Gradle coordinates and plugin ids (limited). */
    val dependencies: List<String>,
    val conventions: CodingConventions,
    /** The files the profile was read from (shown to the user, so nothing is hidden). */
    val inspected: List<String>,
) {
    val isKotlin: Boolean get() = language.startsWith("Kotlin")
    val isJava: Boolean get() = language.startsWith("Java")
    val isWeb: Boolean get() = uiToolkit == UiToolkit.WEB
    val usesCompose: Boolean get() = uiToolkit == UiToolkit.COMPOSE || uiToolkit == UiToolkit.COMPOSE_AND_XML
    fun hasLibrary(name: String): Boolean = libraries.any { it.equals(name, ignoreCase = true) }

    /** The text that goes into a request. Facts only; no file contents. */
    fun render(): String {
        val sb = StringBuilder()
        sb.append("PROJECT PROFILE (detected from the project's own files; follow it):\n")
        sb.append("- Language: ").append(language).append('\n')
        sb.append("- UI toolkit: ").append(uiToolkit.label).append('\n')
        if (!packageName.isNullOrBlank()) sb.append("- Base package: ").append(packageName).append('\n')
        if (!sdk.isNullOrBlank()) sb.append("- ").append(sdk).append('\n')
        if (architecture.isNotEmpty()) sb.append("- Architecture: ").append(architecture.joinToString("; ")).append('\n')
        if (layers.isNotEmpty()) sb.append("- Package folders: ").append(layers.joinToString(", ")).append('\n')
        if (libraries.isNotEmpty()) sb.append("- Libraries in use: ").append(libraries.joinToString(", ")).append('\n')
        if (dependencies.isNotEmpty()) sb.append("- Dependencies: ").append(dependencies.take(MAX_RENDERED_DEPENDENCIES).joinToString(", ")).append('\n')
        val c = conventions
        val conv = ArrayList<String>()
        c.indent?.let { conv += "indent with $it" }
        when (c.trailingCommas) { true -> conv += "trailing commas in multi-line lists"; false -> conv += "no trailing commas"; null -> Unit }
        c.maxLineLength?.let { conv += "lines up to $it characters" }
        conv += c.notes
        if (conv.isNotEmpty()) sb.append("- Coding conventions: ").append(conv.joinToString("; ")).append('\n')
        val text = sb.toString()
        return if (text.length <= MAX_RENDERED_CHARS) text else text.take(MAX_RENDERED_CHARS).substringBeforeLast('\n') + "\n"
    }

    companion object {
        const val MAX_RENDERED_CHARS = 2600
        const val MAX_RENDERED_DEPENDENCIES = 30
        fun unknown(name: String?) = ProjectProfile(name, "unknown", UiToolkit.UNKNOWN, null, null, emptyList(), emptyList(), emptyList(), emptyList(), CodingConventions(null, null, null, emptyList()), emptyList())
    }
}

object ProjectProfiler {
    const val MAX_SAMPLE_SOURCES = 6
    const val MAX_SAMPLE_LAYOUTS = 2
    const val MAX_BUILD_FILES = 6
    const val MAX_CHARS_PER_FILE = 20_000
    private const val MIN_INDENT_SAMPLES = 3

    fun isBuildFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name == "build.gradle" || name == "build.gradle.kts" || name == "libs.versions.toml"
    }

    private fun isCode(path: String) = path.endsWith(".kt") || path.endsWith(".java")
    private fun isMainSource(path: String) = isCode(path) && !path.contains("/test/") && !path.contains("/androidTest/") && !path.startsWith("test/")
    private fun isLayout(path: String) = path.contains("/res/layout") && path.endsWith(".xml")

    /** Which files are worth reading to build a profile (the loader reads them, then calls [analyze]). Deterministic. */
    fun pickFiles(paths: List<String>): List<String> {
        val visible = paths.filter { !ContextPolicy.isHiddenPath(it) && !SecretScanner.isSensitivePath(it) }
        val out = LinkedHashSet<String>()
        visible.filter { isBuildFile(it) }.sortedBy { it.count { c -> c == '/' } }.take(MAX_BUILD_FILES).forEach { out += it }
        visible.filter { it.endsWith("AndroidManifest.xml") }.take(1).forEach { out += it }
        val preferred = listOf("ViewModel", "Repository", "Screen", "Activity", "Fragment", "Api", "Service", "Adapter", "Dao", "UseCase")
        val sources = visible.filter { isMainSource(it) }
            .sortedWith(compareByDescending<String> { p -> preferred.count { p.substringAfterLast('/').contains(it) } }.thenBy { it })
        // One file per folder first, so the sample shows the whole project and not just one package.
        val dirs = HashSet<String>()
        val chosen = ArrayList<String>()
        for (p in sources) if (chosen.size < MAX_SAMPLE_SOURCES && dirs.add(p.substringBeforeLast('/', ""))) chosen += p
        for (p in sources) if (chosen.size < MAX_SAMPLE_SOURCES && p !in chosen) chosen += p
        chosen.forEach { out += it }
        visible.filter { isLayout(it) }.take(MAX_SAMPLE_LAYOUTS).forEach { out += it }
        visible.filter { it.endsWith(".js") || it.endsWith(".html") }.take(2).forEach { out += it }
        return out.toList()
    }

    /** [texts] maps a path to its text (only the files [pickFiles] chose; missing ones are simply absent). */
    fun analyze(projectName: String?, type: ProjectType?, paths: List<String>, texts: Map<String, String>): ProjectProfile {
        val visible = paths.filter { !ContextPolicy.isHiddenPath(it) }
        val buildTexts = texts.filterKeys { isBuildFile(it) }.values.toList()
        val buildAll = buildTexts.joinToString("\n")
        val deps = DependencyExtractor.extract(buildTexts, 60)
        val sources = texts.filterKeys { isCode(it) }
        val layouts = texts.filterKeys { isLayout(it) }
        val manifest = texts.entries.firstOrNull { it.key.endsWith("AndroidManifest.xml") }?.value.orEmpty()
        val sourceText = sources.values.joinToString("\n")

        val language = languageOf(type, visible)
        val ui = uiToolkitOf(type, visible, buildAll, sourceText)
        val packageName = packageOf(manifest, buildAll, sources.values.toList())
        val haystack = (deps.joinToString(" ") + "\n" + importLines(sourceText) + "\n" + sourceText.take(60_000)).lowercase()
        val libraries = librariesOf(haystack, ui)
        val layers = layersOf(visible, packageName)
        val architecture = architectureOf(visible, sourceText, libraries, layers, ui)
        return ProjectProfile(
            projectName = projectName, language = language, uiToolkit = ui, packageName = packageName, sdk = sdkOf(buildAll),
            architecture = architecture, layers = layers, libraries = libraries, dependencies = deps.take(40),
            conventions = conventionsOf(sources, layouts, texts.filterKeys { it.endsWith(".js") }, ui, visible),
            inspected = texts.keys.toList(),
        )
    }

    // ---- language / toolkit ----------------------------------------------------------------------------------------

    private fun languageOf(type: ProjectType?, paths: List<String>): String = when (type) {
        ProjectType.ANDROID_KOTLIN -> "Kotlin (Android)"
        ProjectType.ANDROID_JAVA -> "Java (Android)"
        ProjectType.WEB -> "JavaScript (web: HTML, CSS, JavaScript)"
        null -> {
            val kt = paths.count { it.endsWith(".kt") }
            val java = paths.count { it.endsWith(".java") }
            val web = paths.count { it.endsWith(".html") || it.endsWith(".js") }
            when {
                kt == 0 && java == 0 && web > 0 -> "JavaScript (web: HTML, CSS, JavaScript)"
                kt == 0 && java == 0 -> "unknown"
                kt >= java -> "Kotlin (Android)"
                else -> "Java (Android)"
            }
        }
    }

    private fun uiToolkitOf(type: ProjectType?, paths: List<String>, buildAll: String, sourceText: String): UiToolkit {
        if (type == ProjectType.WEB) return UiToolkit.WEB
        val compose = Regex("""compose\s*=\s*true""").containsMatchIn(buildAll) || buildAll.contains("androidx.compose") ||
            buildAll.contains("plugin.compose") || sourceText.contains("@Composable")
        val xml = paths.any { isLayout(it) } || sourceText.contains("setContentView(R.layout") || sourceText.contains("R.layout.") || sourceText.contains("ViewBinding")
        return when {
            compose && xml -> UiToolkit.COMPOSE_AND_XML
            compose -> UiToolkit.COMPOSE
            xml -> UiToolkit.XML_VIEWS
            type == null && paths.any { it.endsWith(".html") } -> UiToolkit.WEB
            else -> UiToolkit.UNKNOWN
        }
    }

    private fun sdkOf(buildAll: String): String? {
        fun num(name: String) = Regex("""\b$name\s*=?\s*(\d{2})\b""").find(buildAll)?.groupValues?.get(1)
        val parts = listOfNotNull(num("minSdk")?.let { "minSdk $it" }, num("targetSdk")?.let { "targetSdk $it" }, num("compileSdk")?.let { "compileSdk $it" })
        val kotlin = Regex("""org\.jetbrains\.kotlin\.android["')\s]*version\s*["']([\d.]+)["']""").find(buildAll)?.groupValues?.get(1)
        val all = parts + listOfNotNull(kotlin?.let { "Kotlin $it" })
        return if (all.isEmpty()) null else "SDK: " + all.joinToString(", ")
    }

    private fun packageOf(manifest: String, buildAll: String, sources: List<String>): String? {
        Regex("""\bnamespace\s*=\s*["']([\w.]+)["']""").find(buildAll)?.let { return it.groupValues[1] }
        Regex("""\bapplicationId\s*=?\s*["']([\w.]+)["']""").find(buildAll)?.let { return it.groupValues[1] }
        Regex("""<manifest[^>]*\bpackage\s*=\s*"([\w.]+)"""").find(manifest)?.let { return it.groupValues[1] }
        val counts = HashMap<String, Int>()
        val re = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
        for (s in sources) re.find(s)?.groupValues?.get(1)?.let { counts.merge(it, 1) { a, b -> a + b } }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
    }

    // ---- libraries / architecture ----------------------------------------------------------------------------------

    private fun importLines(text: String): String = text.lineSequence().map { it.trim() }.filter { it.startsWith("import ") }.joinToString("\n")

    private val knownLibraries: List<Pair<String, List<String>>> = listOf(
        "Retrofit" to listOf("retrofit"), "OkHttp" to listOf("okhttp"), "Ktor client" to listOf("io.ktor"),
        "Room" to listOf("androidx.room"), "Hilt" to listOf("dagger.hilt", "hilt-android", "hilt.android"), "Koin" to listOf("org.koin", "io.insert-koin"),
        "Navigation Compose" to listOf("navigation-compose", "androidx.navigation.compose", "navhost("),
        "Navigation component" to listOf("navigation-fragment", "androidx.navigation.fragment"),
        "Material 3" to listOf("androidx.compose.material3", "material3"), "Material Components" to listOf("com.google.android.material"),
        "Coil" to listOf("io.coil-kt", "coil.compose", "coil3"), "Glide" to listOf("bumptech.glide"), "Picasso" to listOf("squareup.picasso"),
        "Kotlin coroutines" to listOf("kotlinx-coroutines", "kotlinx.coroutines"), "Kotlin serialization" to listOf("kotlinx-serialization", "kotlinx.serialization"),
        "Gson" to listOf("com.google.code.gson", "com.google.gson"), "Moshi" to listOf("com.squareup.moshi"),
        "DataStore" to listOf("androidx.datastore"), "WorkManager" to listOf("androidx.work"), "Firebase" to listOf("com.google.firebase"),
        "Lifecycle / ViewModel" to listOf("androidx.lifecycle"), "Paging" to listOf("androidx.paging"),
        "JUnit" to listOf("junit"), "MockK" to listOf("io.mockk"), "Mockito" to listOf("org.mockito"),
    )

    private fun librariesOf(haystack: String, ui: UiToolkit): List<String> {
        val out = ArrayList<String>()
        for ((name, needles) in knownLibraries) {
            if (needles.any { haystack.contains(it) }) out += name
        }
        // Material Components are the Views library: do not report it for a pure Compose project that only mentions "material3".
        if (ui == UiToolkit.COMPOSE && "Material Components" in out && !haystack.contains("com.google.android.material:material")) out.remove("Material Components")
        return out
    }

    private fun layersOf(paths: List<String>, packageName: String?): List<String> {
        val dirs = paths.filter { isMainSource(it) }.mapNotNull { p ->
            Regex("""^(?:.*/)?src/main/(?:java|kotlin)/(.+)/[^/]+$""").find(p)?.groupValues?.get(1)
        }
        if (dirs.isEmpty()) return emptyList()
        val base = packageName?.replace('.', '/')?.takeIf { b -> dirs.any { it == b || it.startsWith("$b/") } } ?: commonPrefix(dirs)
        val counts = HashMap<String, Int>()
        for (d in dirs) {
            if (!d.startsWith("$base/")) continue
            val layer = d.removePrefix("$base/").substringBefore('/')
            if (layer.isNotEmpty()) counts.merge(layer, 1) { a, b -> a + b }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(8).map { it.key }
    }

    private fun commonPrefix(dirs: List<String>): String {
        val split = dirs.map { it.split('/') }
        val first = split.first()
        var n = first.size
        for (s in split) {
            var i = 0
            while (i < n && i < s.size && s[i] == first[i]) i++
            n = i
        }
        return first.take(n).joinToString("/")
    }

    private fun architectureOf(paths: List<String>, sourceText: String, libraries: List<String>, layers: List<String>, ui: UiToolkit): List<String> {
        val out = ArrayList<String>()
        val hasViewModel = Regex(""":\s*(?:Android)?ViewModel\s*\(""").containsMatchIn(sourceText) || sourceText.contains("extends ViewModel") ||
            paths.any { it.endsWith("ViewModel.kt") || it.endsWith("ViewModel.java") }
        if (hasViewModel) {
            val state = when {
                sourceText.contains("StateFlow") -> " + StateFlow"
                sourceText.contains("LiveData") -> " + LiveData"
                else -> ""
            }
            out += "MVVM (ViewModel$state)"
        }
        if (paths.any { it.endsWith("Repository.kt") || it.endsWith("Repository.java") } || Regex("""class\s+\w+Repository""").containsMatchIn(sourceText)) out += "Repository pattern"
        if (sourceText.contains("UseCase") || paths.any { it.contains("UseCase") }) out += "Use cases (domain layer)"
        if ("Hilt" in libraries || sourceText.contains("@HiltViewModel") || sourceText.contains("@AndroidEntryPoint")) out += "Dependency injection with Hilt"
        else if ("Koin" in libraries) out += "Dependency injection with Koin"
        if (sourceText.contains("NavHost(")) out += "Navigation Compose (NavHost)"
        if (sourceText.contains("@Database") || sourceText.contains("@Dao")) out += "Room database"
        if (!hasViewModel && ui == UiToolkit.XML_VIEWS) out += "Activities / Fragments with XML layouts"
        val layered = layers.count { it in setOf("data", "domain", "ui", "presentation", "model", "network", "di", "viewmodel", "repository") }
        if (layered >= 2) out += "Layered packages"
        return out
    }

    // ---- conventions -----------------------------------------------------------------------------------------------

    private fun conventionsOf(sources: Map<String, String>, layouts: Map<String, String>, scripts: Map<String, String>, ui: UiToolkit, paths: List<String>): CodingConventions {
        val code = sources.values.toList()
        val notes = ArrayList<String>()
        val indent = if (code.isEmpty()) indentOf(scripts.values.toList()) else indentOf(code)
        val kotlin = sources.keys.any { it.endsWith(".kt") }
        val trailing = if (kotlin) trailingCommasOf(code) else null
        val maxLine = maxLineOf(code)

        if (kotlin && code.any { Regex("""private val _\w+\s*=\s*Mutable""").containsMatchIn(it) }) notes += "private mutable state is exposed as read-only (_state / state)"
        if (ui == UiToolkit.COMPOSE || ui == UiToolkit.COMPOSE_AND_XML) {
            if (code.any { it.contains("modifier: Modifier = Modifier") }) notes += "composables are PascalCase and take `modifier: Modifier = Modifier`"
            if (code.any { it.contains("stringResource(") }) notes += "UI texts come from string resources (stringResource)"
        }
        if (code.isNotEmpty() && code.count { it.contains("/**") } * 100 / code.size >= 30) notes += "public declarations carry KDoc comments"
        val ids = layouts.values.flatMap { l -> Regex("""@\+id/([A-Za-z0-9_]+)""").findAll(l).map { it.groupValues[1] }.toList() }
        if (ids.size >= 3) {
            val prefixed = ids.count { Regex("""^[a-z]{2,4}_[A-Za-z0-9_]+$""").matches(it) }
            val camel = ids.count { Regex("""^[a-z][A-Za-z0-9]*[A-Z][A-Za-z0-9]*$""").matches(it) }
            when {
                prefixed * 100 / ids.size >= 60 -> notes += "view ids use a type prefix (for example btn_login)"
                camel * 100 / ids.size >= 60 -> notes += "view ids are camelCase"
                else -> notes += "view ids are snake_case"
            }
        }
        if (paths.any { it.endsWith("res/values/strings.xml") }) notes += "texts live in res/values/strings.xml"
        if (scripts.isNotEmpty()) {
            val all = scripts.values.joinToString("\n")
            val single = all.count { it == '\'' }
            val double = all.count { it == '"' }
            if (single + double >= 10) notes += if (single > double * 3 / 2) "JavaScript strings use single quotes" else if (double > single * 3 / 2) "JavaScript strings use double quotes" else "mixed quote style"
        }
        return CodingConventions(indent, trailing, maxLine, notes)
    }

    private fun indentOf(files: List<String>): String? {
        val hist = HashMap<String, Int>()
        for (f in files) {
            var prev: String? = null
            for (line in f.lineSequence()) {
                if (line.isBlank()) continue
                val p = prev
                if (p != null && p.trimEnd().endsWith("{") && !p.trimEnd().endsWith("\${")) {
                    val pi = leadingOf(p)
                    val ci = leadingOf(line)
                    val key = when {
                        ci.startsWith(pi) && ci.length > pi.length -> {
                            val extra = ci.substring(pi.length)
                            if (extra.all { it == '\t' }) "tabs" else if (extra.all { it == ' ' } && extra.length in listOf(2, 4, 8)) "${extra.length} spaces" else null
                        }
                        else -> null
                    }
                    if (key != null) hist.merge(key, 1) { a, b -> a + b }
                }
                prev = line
            }
        }
        val total = hist.values.sum()
        if (total < MIN_INDENT_SAMPLES) return null
        val best = hist.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first()
        return if (best.value * 100 / total >= 60) best.key else null
    }

    private fun leadingOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    private fun trailingCommasOf(files: List<String>): Boolean? {
        var with = 0
        var without = 0
        for (f in files) {
            val lines = f.lines().filter { it.isNotBlank() }.map { it.trim() }
            for (i in 0 until lines.size - 1) {
                val next = lines[i + 1]
                if (!(next.startsWith(")") || next.startsWith("]"))) continue
                val cur = lines[i]
                when {
                    cur.endsWith(",") -> with++
                    cur.endsWith("(") || cur.endsWith("{") || cur.endsWith(")") || cur.endsWith("[") || cur.startsWith("//") || cur.startsWith("*") -> Unit
                    else -> without++
                }
            }
        }
        return when {
            with >= 2 && with > without -> true
            without >= 2 && without > with -> false
            else -> null
        }
    }

    private fun maxLineOf(files: List<String>): Int? {
        val lengths = files.flatMap { f -> f.lines().filter { !it.trimStart().startsWith("import ") && !it.trimStart().startsWith("package ") }.map { it.length } }
        if (lengths.size < 30) return null
        val sorted = lengths.sorted()
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        return when {
            p95 <= 100 -> 100
            p95 <= 120 -> 120
            else -> null
        }
    }
}

/**
 * Builds and caches the [ProjectProfile] of one project. Reading is local; nothing is sent. The cache lasts a short time and
 * is dropped when the list of files changes, so a profile never describes a project that no longer exists.
 */
class ProjectProfileProvider(
    private val files: AiProjectFiles,
    private val projectName: String?,
    private val type: ProjectType?,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val ttlMillis: Long = 120_000L,
) {
    private var cached: ProjectProfile? = null
    private var cachedKey: Int = 0
    private var cachedAt: Long = 0L

    fun invalidate() { cached = null }

    suspend fun profile(): ProjectProfile {
        val all = try { files.list() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val key = all.hashCode()
        val now = clockMillis()
        cached?.let { if (key == cachedKey && now - cachedAt < ttlMillis) return it }
        val texts = LinkedHashMap<String, String>()
        for (p in ProjectProfiler.pickFiles(all)) {
            try {
                val t = files.read(p)
                texts[p] = if (t.length > ProjectProfiler.MAX_CHARS_PER_FILE) t.take(ProjectProfiler.MAX_CHARS_PER_FILE) else t
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* a file that cannot be read is simply not part of the sample */ }
        }
        val p = ProjectProfiler.analyze(projectName, type, all, texts)
        cached = p; cachedKey = key; cachedAt = now
        return p
    }
}
