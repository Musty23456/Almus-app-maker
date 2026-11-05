package com.almus.appcreatorpro.debug

/** One static finding and the fixes that could repair it. */
data class Finding(val issue: BuildIssue, val fixes: List<FixProposal> = emptyList())

data class StaticResult(val findings: List<Finding>, val filesChecked: Int) {
    val issues: List<BuildIssue> get() = findings.map { it.issue }
    val fixes: List<FixProposal> get() = findings.flatMap { it.fixes }
}

/**
 * Source-level checks that need no compiler: missing and unused imports, unused locals, names that are declared
 * nowhere, bracket balance, common Kotlin/Java mistakes, malformed XML, missing resources, manifest and Gradle
 * configuration problems.
 *
 * HONEST SCOPE: these are text heuristics. They do not understand types, inheritance or libraries, so they can miss
 * real problems and can occasionally be wrong; every finding carries a [Confidence] and a message that says what was
 * (not) found. They are NOT a replacement for the compiler or for running the app: runtime behaviour (crashes,
 * threading, lifecycle, network) is only hinted at, never proven. Real runtime debugging needs Logcat (see
 * [LogcatSource]).
 */
class StaticDiagnostics {

    fun analyze(files: Map<String, String>): StaticResult {
        val project = ProjectIndex(files)
        val out = ArrayList<Finding>()
        var checked = 0
        for ((path, text) in files.toSortedMap()) {
            if (text.length > MAX_FILE_CHARS) continue
            val lower = path.lowercase()
            when {
                lower.endsWith(".kt") || lower.endsWith(".java") -> { checked++; out += CodeChecker(path, text, project).run() }
                lower.endsWith("androidmanifest.xml") -> { checked++; out += XmlChecker.balance(path, text); out += ManifestChecker(path, text, project).run(); out += ResourceChecker.checkXml(path, text, project) }
                lower.endsWith(".xml") -> { checked++; out += XmlChecker.balance(path, text); out += ResourceChecker.checkXml(path, text, project) }
                lower.endsWith("build.gradle.kts") || lower.endsWith("build.gradle") -> { checked++; out += GradleChecker(path, text, project).run() }
            }
        }
        out += ResourceChecker.checkCodeReferences(files, project)
        out += ProjectChecks.run(project)
        val unique = LinkedHashMap<String, Finding>()
        for (f in out) unique.putIfAbsent(f.issue.id, f)
        val sorted = unique.values.sortedWith(
            compareBy<Finding> { it.issue.severity.rank }.thenBy { it.issue.projectPath ?: "" }.thenBy { it.issue.line ?: 0 },
        )
        return StaticResult(sorted, checked)
    }

    companion object {
        const val MAX_FILE_CHARS = 400_000
    }
}

// ================================================================================================ shared helpers

internal fun staticIssue(
    rule: String,
    category: ErrorCategory,
    severity: Severity,
    message: String,
    path: String,
    line: Int?,
    column: Int? = null,
    suggestion: String? = null,
    confidence: Confidence = Confidence.MEDIUM,
    symbol: String? = null,
): BuildIssue = BuildIssue(
    id = Fingerprint.of("static", rule, path, line, message),
    rule = rule, category = category, severity = severity, message = message,
    file = path, projectPath = path, line = line, column = column,
    suggestion = suggestion, source = IssueSource.STATIC_ANALYSIS, confidence = confidence, symbol = symbol,
)

internal fun fixFor(
    issue: BuildIssue?,
    title: String,
    explanation: String,
    path: String,
    text: String,
    edits: List<CodeEdit>,
    confidence: Confidence = Confidence.MEDIUM,
): FixProposal = FixProposal(
    id = Fingerprint.of("fix", path, title, edits.joinToString("|") { "${it.startLine}-${it.endLineExclusive}:${it.newLines.joinToString("\u0001")}" }),
    issueId = issue?.id, title = title, explanation = explanation, path = path, edits = edits,
    origin = FixOrigin.RULE, confidence = confidence, baseHash = TextEdits.hash(text),
)

/** What the whole project declares, so a file can be checked against its neighbours. */
internal class ProjectIndex(val files: Map<String, String>) {
    /** simple type name -> fully qualified names declared in the project */
    val types = HashMap<String, MutableSet<String>>()
    val packages = HashMap<String, String>()
    /** every name declared with fun/val/var anywhere (for undefined-name checks) */
    val declaredNames = HashSet<String>()
    /** Capitalised functions/values (Composables, constants like SuccessGreen) and enum entries, project-wide. */
    val capitalNames = HashSet<String>()
    val hasSources: Boolean
    val resources = ResourceIndex(files)

    init {
        var any = false
        for ((path, text) in files) {
            val lower = path.lowercase()
            if (!lower.endsWith(".kt") && !lower.endsWith(".java")) continue
            any = true
            val masked = SourceText.mask(text, kotlin = lower.endsWith(".kt"))
            val pkg = PACKAGE.find(masked)?.groupValues?.get(1).orEmpty()
            packages[path] = pkg
            for (m in TYPE_DECL.findAll(masked)) {
                val name = m.groupValues[1]
                types.getOrPut(name) { HashSet() }.add(if (pkg.isEmpty()) name else "$pkg.$name")
            }
            for (m in NAME_DECL.findAll(masked)) {
                declaredNames.add(m.groupValues[1])
                if (m.groupValues[1].firstOrNull()?.isUpperCase() == true) capitalNames.add(m.groupValues[1])
            }
            for (m in ENUM_BODY.findAll(masked)) capitalNames.addAll(enumEntries(m.groupValues[1]))
        }
        hasSources = any
    }

    fun enumEntries(body: String): List<String> =
        body.substringBefore(';').split(',').mapNotNull { Regex("^\\s*([A-Za-z_]\\w*)").find(it)?.groupValues?.get(1) }

    fun namespace(): String? {
        for ((path, text) in files) {
            if (path.endsWith("build.gradle.kts") || path.endsWith("build.gradle")) {
                NAMESPACE.find(text)?.let { return it.groupValues[1] }
            }
        }
        for ((path, text) in files) {
            if (path.endsWith("AndroidManifest.xml")) PACKAGE_ATTR.find(text)?.let { return it.groupValues[1] }
        }
        return null
    }

    fun targetSdk(): Int? {
        for ((path, text) in files) {
            if (path.endsWith("build.gradle.kts") || path.endsWith("build.gradle")) {
                TARGET_SDK.find(text)?.let { return it.groupValues[1].toIntOrNull() }
            }
        }
        return null
    }

    companion object {
        val PACKAGE = Regex("(?m)^\\s*package\\s+([\\w.]+)")
        val TYPE_DECL = Regex("\\b(?:class|interface|object|enum\\s+class|typealias|enum|record|@interface)\\s+([A-Z]\\w*)")
        val NAME_DECL = Regex("\\b(?:val|var|fun)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?(\\w+)")
        val ENUM_BODY = Regex("\\benum\\s+(?:class\\s+)?\\w+[^{;]*\\{([^}]*)\\}")
        val NAMESPACE = Regex("namespace\\s*(?:=)?\\s*[\"']([\\w.]+)[\"']")
        val PACKAGE_ATTR = Regex("<manifest[^>]*?\\bpackage\\s*=\\s*\"([\\w.]+)\"")
        val TARGET_SDK = Regex("targetSdk(?:Version)?\\s*(?:=)?\\s*(\\d+)")
    }
}

// ================================================================================================ code (Kotlin / Java)

internal class CodeChecker(private val path: String, private val text: String, private val project: ProjectIndex) {
    private val isKotlin = path.lowercase().endsWith(".kt")
    private val masked = SourceText.mask(text, isKotlin)
    private val lineIndex = SourceText.LineIndex(text)
    private val lines = TextEdits.lines(text)
    private val category = if (isKotlin) ErrorCategory.KOTLIN_COMPILATION else ErrorCategory.JAVA_COMPILATION
    private val findings = ArrayList<Finding>()
    private val importRegex = if (isKotlin) Regex("(?m)^[ \\t]*import\\s+([\\w.]+)(\\.\\*)?(?:\\s+as\\s+(\\w+))?[ \\t]*;?[ \\t]*$")
    else Regex("(?m)^[ \\t]*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;[ \\t]*$")

    private data class Import(val fqn: String, val simple: String, val wildcard: Boolean, val line: Int, val isStatic: Boolean)

    private val imports: List<Import> = run {
        val list = ArrayList<Import>()
        for (m in importRegex.findAll(masked)) {
            val line = lineIndex.lineOf(m.range.first)
            if (isKotlin) {
                val fqn = m.groupValues[1]
                val wildcard = m.groupValues[2].isNotEmpty()
                val alias = m.groupValues[3]
                list += Import(fqn, alias.ifEmpty { fqn.substringAfterLast('.') }, wildcard, line, false)
            } else {
                val fqn = m.groupValues[2]
                list += Import(fqn, fqn.substringAfterLast('.'), m.groupValues[3].isNotEmpty(), line, m.groupValues[1].isNotEmpty())
            }
        }
        list
    }

    /** Code with import/package lines blanked, so usage counts do not count the import itself. */
    private val body: String = run {
        val sb = StringBuilder(masked)
        for (m in Regex("(?m)^[ \\t]*(?:import|package)\\b[^\\n]*").findAll(masked)) {
            for (k in m.range) if (sb[k] != '\n') sb.setCharAt(k, ' ')
        }
        sb.toString()
    }

    private val filePackage: String = project.packages[path].orEmpty()

    fun run(): List<Finding> {
        brackets()
        if (findings.none { it.issue.severity == Severity.ERROR && it.issue.rule.endsWith("brackets") }) {
            importChecks()
            unusedLocals()
            if (isKotlin) { undefinedNames(); valReassign() }
            lineChecks()
            unreachableCode()
            if (!isKotlin) missingSemicolon()
            runtimeRisks()
        }
        return findings
    }

    private fun add(issue: BuildIssue, fixes: List<FixProposal> = emptyList()) { findings += Finding(issue, fixes) }

    private fun lineOfOffset(o: Int) = lineIndex.lineOf(o)

    private fun colOfOffset(o: Int) = o - lineIndex.startOf(lineIndex.lineOf(o)) + 1

    // ---------------------------------------------------------------------------------------- brackets
    private fun brackets() {
        val problems = SourceText.bracketProblems(body, max = 1)
        for (p in problems) {
            val issue = staticIssue(
                "static.brackets", category, Severity.ERROR, p.message, path, p.line,
                suggestion = "Check the brackets around this line: every ( [ { needs a matching closing bracket in the right order.",
                confidence = Confidence.HIGH,
            )
            add(issue)
        }
    }

    // ---------------------------------------------------------------------------------------- imports
    private fun importInsertion(newFqn: String): CodeEdit {
        val stmt = if (isKotlin) "import $newFqn" else "import $newFqn;"
        val sameKind = imports.filter { !it.isStatic }
        if (sameKind.isNotEmpty()) {
            val before = sameKind.firstOrNull { it.fqn > newFqn && !it.wildcard }
            return if (before != null) CodeEdit.insert(before.line, listOf(stmt)) else CodeEdit.insert(sameKind.maxOf { it.line } + 1, listOf(stmt))
        }
        val pkgLine = Regex("(?m)^[ \\t]*package\\b").find(masked)?.let { lineOfOffset(it.range.first) }
        if (pkgLine == null) return CodeEdit.insert(1, listOf(stmt, ""))
        val nextBlank = lines.getOrNull(pkgLine)?.isBlank() == true
        return if (nextBlank) CodeEdit.insert(pkgLine + 1, listOf(stmt)) else CodeEdit.insert(pkgLine + 1, listOf("", stmt, ""))
    }

    private fun importChecks() {
        val importedSimple = imports.filter { !it.wildcard }.map { it.simple }.toSet()
        val wildcardPackages = imports.filter { it.wildcard }.map { it.fqn }.toSet()
        val declaredHere = HashSet<String>()
        for (m in ProjectIndex.TYPE_DECL.findAll(body)) declaredHere.add(m.groupValues[1])
        for (m in ProjectIndex.ENUM_BODY.findAll(body)) declaredHere.addAll(project.enumEntries(m.groupValues[1]))
        declaredHere.addAll(project.capitalNames)
        val typeParams = HashSet<String>()
        for (m in Regex("(?:class|interface|fun|typealias|record)\\s*<([^>]*)>|class\\s+\\w+\\s*<([^>]*)>|<\\s*([A-Z]\\w*)\\s+extends\\b|(?:public|private|protected|static|final)\\s+<([^>]*)>").findAll(body)) {
            for (g in 1..4) for (t in Regex("[A-Z]\\w*").findAll(m.groupValues[g])) typeParams.add(t.value)
        }
        val hasStaticWildcard = imports.any { it.isStatic && it.wildcard }
        val missing = ArrayList<Triple<String, List<String>, Int>>() // name, candidates, first offset
        val seen = HashSet<String>()
        val usage = Regex("(?<![\\w.$])([A-Z][A-Za-z0-9_]*)(?![\\w$])")
        for (m in usage.findAll(body)) {
            val name = m.groupValues[1]
            if (!seen.add(name)) continue
            if (name.length == 1 || name.all { it.isUpperCase() || it.isDigit() || it == '_' }) continue
            if (name in importedSimple || name in declaredHere || name in typeParams) continue
            if (name == "R" || name == "BuildConfig" || name.endsWith("Binding")) continue
            if (isKotlin && name in KnownSymbols.kotlinDefault) continue
            if (!isKotlin && name in KnownSymbols.javaDefault) continue
            val projectTypes = project.types[name]
            if (projectTypes != null) {
                if (projectTypes.any { it.substringBeforeLast('.', "") == filePackage }) continue
                if (wildcardPackages.any { wp -> projectTypes.any { it.substringBeforeLast('.', "") == wp } }) continue
                if (projectTypes.size == 1) missing += Triple(name, projectTypes.toList(), m.range.first)
                continue
            }
            var candidates = KnownSymbols.importable[name]?.toList()
            if (candidates == null && !isKotlin) KnownSymbols.javaOnlyImportable[name]?.let { candidates = listOf(it) }
            if (candidates != null) {
                val pkgs = candidates!!
                if (wildcardPackages.any { wp -> pkgs.any { it.substringBeforeLast('.') == wp } }) continue
                var narrowed = pkgs
                if (pkgs.size > 1) {
                    val compose = text.contains("@Composable") || imports.any { it.fqn.startsWith("androidx.compose") }
                    narrowed = if (compose) pkgs.filter { it.startsWith("androidx.compose") } else pkgs.filter { !it.startsWith("androidx.compose") }
                }
                missing += Triple(name, narrowed.ifEmpty { pkgs }, m.range.first)
                continue
            }
        }

        val allFixItems = ArrayList<Pair<BuildIssue, String>>()
        for ((name, candidates, offset) in missing) {
            val line = lineOfOffset(offset)
            val fqn = candidates.singleOrNull()
            val issue = staticIssue(
                "static.missing-import", category, Severity.ERROR, "`$name` is used but not imported.", path, line, colOfOffset(offset),
                suggestion = if (fqn != null) "Add `import $fqn`." else "Add one of: ${candidates.joinToString { "`import $it`" }}.",
                confidence = if (fqn != null) Confidence.MEDIUM else Confidence.LOW, symbol = name,
            )
            if (fqn != null) {
                allFixItems += issue to fqn
                add(issue, listOf(fixFor(issue, "Add import $fqn", "Adds `import $fqn` so `$name` can be resolved.", path, text, listOf(importInsertion(fqn)))))
            } else add(issue)
        }
        if (allFixItems.size >= 2) {
            val sorted = allFixItems.map { it.second }.sorted()
            val stmt = { f: String -> if (isKotlin) "import $f" else "import $f;" }
            val last = imports.filter { !it.isStatic }.maxOfOrNull { it.line }
            val edit = if (last != null) CodeEdit.insert(last + 1, sorted.map(stmt)) else {
                val e = importInsertion(sorted.first())
                CodeEdit(e.startLine, e.endLineExclusive, e.newLines.flatMap { if (it.startsWith("import ")) sorted.map(stmt) else listOf(it) })
            }
            add(
                staticIssue("static.missing-imports-summary", category, Severity.INFO, "${sorted.size} imports are missing in this file.", path, null,
                    suggestion = "Use \"Add all missing imports\" in the fix list.", confidence = Confidence.MEDIUM),
                listOf(fixFor(null, "Add all ${sorted.size} missing imports", sorted.joinToString("\n"), path, text, listOf(edit))),
            )
        }

        // Names that nothing declares: only reported when the file has no wildcard imports that could supply them.
        if (imports.none { it.wildcard }) {
            for (m in usage.findAll(body)) {
                val name = m.groupValues[1]
                if (name.length < 3 || name.all { it.isUpperCase() || it.isDigit() || it == '_' }) continue
                if (name in importedSimple || name in declaredHere || name in typeParams || name == "R" || name == "BuildConfig" || name.endsWith("Binding")) continue
                if (project.types.containsKey(name)) continue
                if (KnownSymbols.importable.containsKey(name) || KnownSymbols.javaOnlyImportable.containsKey(name)) continue
                if (KnownSymbols.kotlinDefault.contains(name) || KnownSymbols.javaDefault.contains(name)) continue
                if (hasStaticWildcard) continue
                if (!seen.add("undef:$name")) continue
                // A capitalised name used like a type or a constructor call.
                val after = body.substring(m.range.last + 1).trimStart(' ', '\t')
                val looksLikeType = after.startsWith("(") || after.startsWith("<") || after.startsWith(".") || after.startsWith(">") || after.startsWith(",") || after.startsWith(")") || after.startsWith("?") || after.startsWith("=") || after.startsWith(":")
                val before = body.substring(maxOf(0, m.range.first - 12), m.range.first)
                val typePosition = before.trimEnd().endsWith(":") || before.trimEnd().endsWith("<") || before.trimEnd().endsWith("new") || before.trimEnd().endsWith("(") || before.trimEnd().endsWith(",") || Regex("(?:val|var|return|is|as|throw)\\s*$").containsMatchIn(before) || (!isKotlin && looksLikeType)
                if (!typePosition && !after.startsWith("(")) continue
                val line = lineOfOffset(m.range.first)
                add(
                    staticIssue(
                        "static.undefined-type", category, Severity.WARNING,
                        "`$name` is not declared in the project, not imported and not a standard class.", path, line, colOfOffset(m.range.first),
                        suggestion = "Check the spelling of `$name`. If it comes from a library, add the library and the import; if it is your own class, create it or import it.",
                        confidence = Confidence.LOW, symbol = name,
                    ),
                )
            }
        }

        unusedImports(importedSimple)
    }

    private fun unusedImports(importedSimple: Set<String>) {
        val unused = ArrayList<Pair<Import, BuildIssue>>()
        for (imp in imports) {
            if (imp.wildcard || imp.isStatic) continue
            if (imp.simple in KnownSymbols.operatorNames) continue
            if (SourceText.wordCount(body, imp.simple) > 0) continue
            // KDoc / Javadoc references count as use.
            if (Regex("\\[${Regex.escape(imp.simple)}]|@(?:link|see|throws)\\s+${Regex.escape(imp.simple)}\\b").containsMatchIn(text)) continue
            val issue = staticIssue(
                "static.unused-import", ErrorCategory.CODE_QUALITY, Severity.WARNING, "Unused import `${imp.fqn}`.", path, imp.line,
                suggestion = "Remove the import line.", confidence = Confidence.HIGH, symbol = imp.simple,
            )
            unused += imp to issue
        }
        for ((imp, issue) in unused) {
            add(issue, listOf(fixFor(issue, "Remove unused import ${imp.simple}", "Deletes line ${imp.line}.", path, text, listOf(CodeEdit.delete(imp.line)), Confidence.HIGH)))
        }
        if (unused.size >= 2) {
            val edits = unused.map { CodeEdit.delete(it.first.line) }
            add(
                staticIssue("static.unused-imports-summary", ErrorCategory.CODE_QUALITY, Severity.INFO, "${unused.size} unused imports in this file.", path, null, confidence = Confidence.HIGH),
                listOf(fixFor(null, "Remove all ${unused.size} unused imports", unused.joinToString("\n") { it.first.fqn }, path, text, edits, Confidence.HIGH)),
            )
        }
    }

    // ---------------------------------------------------------------------------------------- locals
    private data class Block(val isFunction: Boolean)

    /** Offsets of `val`/`var` (Kotlin) or typed declarations (Java) inside a function body. */
    private fun localDeclarations(): List<Triple<String, Int, Int>> {
        val result = ArrayList<Triple<String, Int, Int>>() // name, offset of name, line
        val declRegex = if (isKotlin) Regex("(?m)^[ \\t]*(?:val|var)\\s+([a-z_]\\w*)\\b")
        else Regex("(?m)^[ \\t]*(?:final\\s+)?(?:int|long|double|float|boolean|char|byte|short|String|[A-Z]\\w*(?:<[^>;=]*>)?(?:\\[\\])*)\\s+([a-z_]\\w*)\\s*(?:=|;)")
        val decls = declRegex.findAll(body).toList()
        if (decls.isEmpty()) return result
        val stack = ArrayList<Block>()
        var next = 0
        var lastBoundary = 0
        for (i in body.indices) {
            while (next < decls.size && decls[next].range.first <= i) {
                val m = decls[next]
                if (stack.any { it.isFunction }) {
                    val g = m.groups[1]!!
                    result += Triple(g.value, g.range.first, lineOfOffset(g.range.first))
                }
                next++
            }
            when (body[i]) {
                '{' -> {
                    val header = body.substring(lastBoundary, i)
                    val isFun = if (isKotlin) Regex("\\bfun\\b|\\binit\\b|\\bconstructor\\b").containsMatchIn(header)
                    else Regex("\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\s*$").containsMatchIn(header) &&
                        !Regex("^\\s*(?:\\}\\s*)?(?:if|for|while|switch|catch|synchronized|else\\s+if|try)\\b").containsMatchIn(header.trimStart()) &&
                        !header.contains(" new ") && !header.contains("->")
                    stack.add(Block(isFun || (stack.lastOrNull()?.isFunction == true)))
                    lastBoundary = i + 1
                }
                '}' -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); lastBoundary = i + 1 }
                ';' -> lastBoundary = i + 1
            }
        }
        return result
    }

    private fun unusedLocals() {
        for ((name, offset, line) in localDeclarations()) {
            if (name == "_" || name.startsWith("_")) continue
            if (SourceText.wordCount(body, name) != 1) continue
            if (line >= 2 && lines[line - 2].trim().startsWith("@")) continue // annotated (for example a test rule)
            val declLine = lines[line - 1]
            val simple = if (isKotlin) Regex("^\\s*(?:val|var)\\s+$name(?:\\s*:\\s*[\\w<>?,. ]+)?\\s*=\\s*[\\w.\"'+\\-* /]+\\s*$").matches(declLine.trimEnd('\r'))
            else Regex("^\\s*(?:final\\s+)?[\\w<>\\[\\]]+\\s+$name\\s*=\\s*[\\w.\"'+\\-* /]+;\\s*$").matches(declLine.trimEnd('\r'))
            val issue = staticIssue(
                "static.unused-variable", ErrorCategory.CODE_QUALITY, Severity.WARNING, "Variable `$name` is declared but never used.", path, line, colOfOffset(offset),
                suggestion = "Remove it, or use it. (Heuristic: the name appears only once in this file.)", confidence = Confidence.MEDIUM, symbol = name,
            )
            val fixes = if (simple) listOf(fixFor(issue, "Remove unused variable $name", "Deletes line $line (a plain assignment with no function call).", path, text, listOf(CodeEdit.delete(line)))) else emptyList()
            add(issue, fixes)
        }
    }

    // ---------------------------------------------------------------------------------------- undefined names (Kotlin)
    private fun undefinedNames() {
        val hasSupertypes = Regex("\\b(?:class|object|interface)\\s+\\w+\\s*(?:<[^>]*>)?\\s*(?:\\((?:[^()]|\\([^()]*\\))*\\))?\\s*:\\s*[A-Za-z]").containsMatchIn(body) ||
            Regex("\\bobject\\s*:").containsMatchIn(body)
        val receiverLambdas = Regex("\\bfun\\s+(?:<[^>]*>\\s*)?[\\w<>?]+\\.\\w+\\s*\\(|\\b(?:apply|run|with|buildString|buildList|buildMap|build|T\\.\\(\\)|object\\s*:)\\b|\\.\\(\\)\\s*->|@Composable|\\bby\\b|\\bdsl\\b").containsMatchIn(body)
        val pure = !hasSupertypes && !receiverLambdas

        val declared = HashSet<String>(KnownSymbols.kotlinFunctions)
        declared += KnownSymbols.kotlinKeywords
        declared += project.declaredNames
        for (imp in imports) declared += imp.simple
        Regex("\\b(?:val|var|fun|class|object|interface|typealias)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?(\\w+)").findAll(body).forEach { declared += it.groupValues[1] }
        Regex("\\b(?:val|var)\\s*\\(([^)]*)\\)").findAll(body).forEach { m -> Regex("\\w+").findAll(m.groupValues[1]).forEach { declared += it.value } }
        Regex("\\bfor\\s*\\(\\s*(?:\\(([^)]*)\\)|(\\w+))").findAll(body).forEach { m ->
            Regex("\\w+").findAll(m.groupValues[1] + " " + m.groupValues[2]).forEach { declared += it.value }
        }
        Regex("\\bcatch\\s*\\(\\s*(\\w+)").findAll(body).forEach { declared += it.groupValues[1] }
        Regex("\\{\\s*([\\w,\\s():?<>]+?)\\s*->").findAll(body).forEach { m -> Regex("\\w+").findAll(m.groupValues[1]).forEach { declared += it.value } }
        Regex("\\b(\\w+)\\s*:(?!:)").findAll(body).forEach { declared += it.groupValues[1] }
        Regex("@(\\w+)").findAll(body).forEach { declared += it.groupValues[1] }

        val ident = Regex("(?<![\\w$.@])([a-z_][A-Za-z0-9_]*)(?![\\w$])")
        val reported = HashSet<String>()
        val localDeclared = HashSet<String>()
        Regex("\\b(?:val|var|fun)\\s+(?:<[^>]*>\\s*)?(\\w+)").findAll(body).forEach { localDeclared += it.groupValues[1] }
        val candidates = localDeclared.filter { it.length >= 5 }
        for (m in ident.findAll(body)) {
            val name = m.groupValues[1]
            if (name in declared || name in reported) continue
            val start = m.range.first
            // after "::" it is a member reference; in an argument list "name =" is a named argument
            if (start >= 2 && body.substring(start - 2, start) == "::") continue
            val prev = body.substring(0, start).trimEnd().lastOrNull()
            val next = body.substring(m.range.last + 1).trimStart(' ', '\t')
            if (next.startsWith("=") && !next.startsWith("==") && (prev == '(' || prev == ',')) continue
            if (prev == '"' || prev == '\'') continue
            val limit = if (name.length >= 8) 2 else 1
            val close = if (name.length >= 5) candidates.filter { editDistance(it, name) <= limit && it != name }.sorted() else emptyList()
            if (!pure && close.isEmpty()) continue
            if (name.length <= 1) continue
            reported += name
            val line = lineOfOffset(start)
            val didYouMean = close.singleOrNull()
            val issue = staticIssue(
                "static.undefined-name", ErrorCategory.KOTLIN_COMPILATION, Severity.WARNING,
                "`$name` is not declared in this file, its imports or the project" + (if (didYouMean != null) " - did you mean `$didYouMean`?" else "."), path, line, colOfOffset(start),
                suggestion = if (didYouMean != null) "Probably a typo: use `$didYouMean`." else "Declare `$name`, fix the spelling, or import it. (Heuristic: only checked in files without superclasses or receiver lambdas.)",
                confidence = if (didYouMean != null) Confidence.MEDIUM else Confidence.LOW, symbol = name,
            )
            val fixes = if (didYouMean != null) {
                val newLine = replaceWord(lines[line - 1], name, didYouMean)
                if (newLine != lines[line - 1]) listOf(fixFor(issue, "Rename $name to $didYouMean", "Replaces `$name` with `$didYouMean` on line $line.", path, text, listOf(CodeEdit.replace(line, newLine)), Confidence.MEDIUM)) else emptyList()
            } else emptyList()
            add(issue, fixes)
        }
    }

    private fun replaceWord(line: String, from: String, to: String): String =
        Regex("(?<![\\w$.])${Regex.escape(from)}(?![\\w$])").replace(line, to)

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 2) return 3
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return dp[a.length][b.length]
    }

    // ---------------------------------------------------------------------------------------- val reassignment (Kotlin)
    private fun valReassign() {
        val valDecls = HashMap<String, Int>()
        val otherDecls = HashSet<String>()
        for (m in Regex("(?m)^[ \\t]*(?:private\\s+|internal\\s+|protected\\s+|public\\s+|const\\s+|override\\s+)*(val|var)\\s+([a-z_]\\w*)\\s*(:[^=\\n]+)?(=)?").findAll(body)) {
            val kind = m.groupValues[1]
            val name = m.groupValues[2]
            if (kind == "val" && m.groupValues[4] == "=") valDecls.putIfAbsent(name, lineOfOffset(m.range.first)) else otherDecls.add(name)
        }
        Regex("\\b(\\w+)\\s*:(?!:)").findAll(body).forEach { otherDecls += it.groupValues[1] }
        Regex("\\bfor\\s*\\(\\s*(\\w+)").findAll(body).forEach { otherDecls += it.groupValues[1] }
        for ((name, declLine) in valDecls) {
            if (name in otherDecls) continue
            val declOffset = lineIndex.startOf(declLine)
            val assign = Regex("(?<![\\w$.])${Regex.escape(name)}\\s*(?:=(?!=)|\\+\\+|--)").findAll(body).firstOrNull { m ->
                if (m.range.first <= declOffset) return@firstOrNull false
                val line = lineOfOffset(m.range.first)
                if (line == declLine || Regex("\\b(?:val|var)\\s+${Regex.escape(name)}\\b").containsMatchIn(lines[line - 1])) return@firstOrNull false
                val prevChar = body.substring(0, m.range.first).trimEnd().lastOrNull()
                if (prevChar == '(' || prevChar == ',') return@firstOrNull false // named argument
                // Still inside the block that declared it? (a closing brace in between ends its scope)
                var depth = 0
                for (k in declOffset until m.range.first) {
                    if (body[k] == '{') depth++ else if (body[k] == '}') { depth--; if (depth < 0) return@firstOrNull false }
                }
                true
            } ?: continue
            val line = lineOfOffset(assign.range.first)
            val issue = staticIssue(
                "static.val-reassign", ErrorCategory.KOTLIN_COMPILATION, Severity.ERROR, "`$name` is a `val` (declared on line $declLine) but is assigned again.", path, line, colOfOffset(assign.range.first),
                suggestion = "Change `val $name` to `var $name` on line $declLine if it must change.", confidence = Confidence.MEDIUM, symbol = name,
            )
            val declText = lines[declLine - 1]
            val fix = if (Regex("\\bval\\s+${Regex.escape(name)}\\b").containsMatchIn(declText)) {
                listOf(fixFor(issue, "Make $name a var", "Changes `val` to `var` on line $declLine.", path, text, listOf(CodeEdit.replace(declLine, Regex("\\bval(\\s+${Regex.escape(name)}\\b)").replaceFirst(declText, "var$1")))))
            } else emptyList()
            add(issue, fix)
        }
    }

    // ---------------------------------------------------------------------------------------- single-line mistakes
    private fun lineChecks() {
        val uiFile = Regex("Activity\\b|Fragment\\b|ViewModel\\b|@Composable").containsMatchIn(body)
        for (m in Regex("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}").findAll(body)) {
            add(
                staticIssue("static.empty-catch", ErrorCategory.RUNTIME_RISK, Severity.WARNING, "Empty catch block: the exception is silently ignored.", path, lineOfOffset(m.range.first),
                    suggestion = "Handle the exception or report it in a way that does not hide the failure.", confidence = Confidence.HIGH),
            )
        }
        if (isKotlin) {
            for (m in Regex("!!(?!=)").findAll(body)) {
                add(
                    staticIssue("static.not-null-assertion", ErrorCategory.RUNTIME_RISK, Severity.WARNING, "`!!` crashes with a NullPointerException when the value is null.", path, lineOfOffset(m.range.first), colOfOffset(m.range.first),
                        suggestion = "Use `?.`, `?:` or an `if (x != null)` check instead.", confidence = Confidence.HIGH),
                )
            }
            for (m in Regex("(?m)\\bif\\s*\\(\\s*[\\w.]+\\s=\\s[^=]").findAll(body)) {
                add(
                    staticIssue("static.assignment-in-condition", category, Severity.ERROR, "Assignment used as a condition (`=` instead of `==`).", path, lineOfOffset(m.range.first), colOfOffset(m.range.first),
                        suggestion = "Kotlin assignments are not expressions. Use `==` to compare.", confidence = Confidence.MEDIUM),
                )
            }
            for (m in Regex("GlobalScope\\s*\\.").findAll(body)) {
                add(
                    staticIssue("static.global-scope", ErrorCategory.RUNTIME_RISK, Severity.WARNING, "GlobalScope coroutines are never cancelled and can leak.", path, lineOfOffset(m.range.first),
                        suggestion = "Use viewModelScope, lifecycleScope or your own CoroutineScope that you cancel.", confidence = Confidence.HIGH),
                )
            }
            if (uiFile) for (m in Regex("\\brunBlocking\\b").findAll(body)) {
                add(
                    staticIssue("static.run-blocking-ui", ErrorCategory.RUNTIME_RISK, Severity.WARNING, "runBlocking in UI code blocks the main thread and can freeze the app (ANR).", path, lineOfOffset(m.range.first),
                        suggestion = "Launch a coroutine (viewModelScope.launch / lifecycleScope.launch) instead.", confidence = Confidence.MEDIUM),
                )
            }
        } else {
            for (m in Regex("(?:\"[^\"\\n]*\"\\s*[!=]=\\s*[\\w(])|(?:[\\w)]\\s*[!=]=\\s*\"[^\"\\n]*\")").findAll(body)) {
                val line = lineOfOffset(m.range.first)
                add(
                    staticIssue("static.string-reference-compare", ErrorCategory.RUNTIME_RISK, Severity.WARNING, "Strings compared with == / != compare references, not text.", path, line, colOfOffset(m.range.first),
                        suggestion = "Use `a.equals(b)` (or `\"literal\".equals(a)`) to compare text.", confidence = Confidence.HIGH),
                )
            }
            for (m in Regex("(?m)\\bif\\s*\\(\\s*[\\w.]+\\s=\\s[^=]").findAll(body)) {
                add(
                    staticIssue("static.assignment-in-condition", category, Severity.WARNING, "Assignment inside a condition (`=` instead of `==`?).", path, lineOfOffset(m.range.first), colOfOffset(m.range.first),
                        suggestion = "If you meant to compare, use `==`.", confidence = Confidence.MEDIUM),
                )
            }
        }
        for (m in Regex("(?<![\\w.)\\]])\\s/\\s*0(?![\\w.])").findAll(body)) {
            add(
                staticIssue("static.division-by-zero", ErrorCategory.RUNTIME_RISK, Severity.WARNING, "Division by the literal 0 (integers throw ArithmeticException).", path, lineOfOffset(m.range.first),
                    suggestion = "Check the divisor before dividing.", confidence = Confidence.MEDIUM),
            )
        }
        if (uiFile) for (m in Regex("Thread\\s*\\.\\s*sleep\\s*\\(").findAll(body)) {
            add(
                staticIssue("static.sleep-ui", ErrorCategory.RUNTIME_RISK, Severity.WARNING, "Thread.sleep in UI code freezes the screen when it runs on the main thread.", path, lineOfOffset(m.range.first),
                    suggestion = "Use `delay()` in a coroutine, or `Handler.postDelayed`.", confidence = Confidence.MEDIUM),
            )
        }
        val background = Regex("\\b(?:Thread|Executor|launch|async|withContext|AsyncTask|Dispatchers|thread)\\b").containsMatchIn(body)
        if (uiFile && !background) for (m in Regex("\\.\\s*(?:openConnection|openStream)\\s*\\(").findAll(body)) {
            add(
                staticIssue("static.network-main-thread", ErrorCategory.RUNTIME_RISK, Severity.WARNING, "Network call in UI code with no background thread in sight: Android throws NetworkOnMainThreadException.", path, lineOfOffset(m.range.first),
                    suggestion = "Run it on Dispatchers.IO (withContext) or a background executor.", confidence = Confidence.MEDIUM),
            )
        }
    }

    // ---------------------------------------------------------------------------------------- flow checks
    private fun unreachableCode() {
        val ls = body.split("\n")
        for (i in 0 until ls.size - 1) {
            val cur = ls[i].trim()
            if (!(cur.startsWith("return") && (cur == "return" || cur[6] == ' ' || cur[6] == ';' || cur[6] == '@'))) continue
            if (cur.endsWith("(") || cur.endsWith(",") || cur.endsWith("+") || cur.endsWith("&&") || cur.endsWith("||") || cur.endsWith("?") || cur.endsWith(":") || cur.endsWith("{") || cur.endsWith("->")) continue
            if (!isKotlin && !cur.endsWith(";")) continue
            var j = i + 1
            while (j < ls.size && ls[j].isBlank()) j++
            if (j >= ls.size) continue
            val next = ls[j].trim()
            if (next.startsWith("}") || next.startsWith("case ") || next.startsWith("default") || next.startsWith(")") || next.startsWith(".") ||
                next.startsWith("else") || next.startsWith("catch") || next.startsWith("finally") || next.startsWith("->") || next.startsWith("?") || next.startsWith("&&") || next.startsWith("||")
            ) continue
            if (next.contains("->")) continue
            add(
                staticIssue(
                    "static.unreachable-code", category, if (isKotlin) Severity.WARNING else Severity.ERROR,
                    "This code can never run: it follows a `return` in the same block.", path, j + 1,
                    suggestion = "Remove the code after `return`, or move the `return` below it.", confidence = Confidence.MEDIUM,
                ),
            )
        }
    }

    private fun missingSemicolon() {
        val ls = body.split("\n")
        val simpleStatement = Regex("^\\s*(?:final\\s+)?(?:int|long|double|float|boolean|char|String|var|[A-Z]\\w*(?:<[^>]*>)?)\\s+\\w+\\s*=\\s*[^;{}]*[\\w)\\]\"'\\d]\\s*$|^\\s*(?:System\\.out\\.println|[a-z]\\w*(?:\\.\\w+)*)\\s*\\(.*\\)\\s*$|^\\s*(?:return\\s+[^;{}]+|break|continue)\\s*$")
        for (i in 0 until ls.size - 1) {
            val cur = ls[i]
            if (cur.isBlank() || !simpleStatement.matches(cur)) continue
            if (cur.trim().startsWith("@") || Regex("^\\s*(?:if|for|while|switch|else|try|catch|synchronized)\\b").containsMatchIn(cur)) continue
            var j = i + 1
            while (j < ls.size && ls[j].isBlank()) j++
            if (j >= ls.size) continue
            val next = ls[j].trim()
            if (next.startsWith(".") || next.startsWith(")") || next.startsWith("{") || next.startsWith("+") || next.startsWith("-") || next.startsWith("*") || next.startsWith("/") ||
                next.startsWith("&&") || next.startsWith("||") || next.startsWith("?") || next.startsWith(":") || next.startsWith("=") || next.startsWith(",") || next.startsWith("else")) continue
            val line = i + 1
            val issue = staticIssue(
                "static.missing-semicolon", ErrorCategory.JAVA_COMPILATION, Severity.ERROR, "This statement is probably missing a `;` at the end.", path, line, cur.trimEnd().length + 1,
                suggestion = "Add `;` at the end of the line.", confidence = Confidence.MEDIUM,
            )
            val orig = lines[line - 1]
            add(issue, listOf(fixFor(issue, "Add ; at the end of line $line", "Appends a semicolon.", path, text, listOf(CodeEdit.replace(line, orig.trimEnd() + ";")))))
        }
    }

    private fun runtimeRisks() {
        // Activities: declared in the manifest?
        val manifest = project.files.entries.firstOrNull { it.key.endsWith("AndroidManifest.xml") } ?: return
        val activityClass = Regex("\\bclass\\s+(\\w+)[^{]*?(?:\\)\\s*:\\s*|\\s*:\\s*|\\bextends\\s+)(?:AppCompatActivity|ComponentActivity|Activity|FragmentActivity)\\b").find(body) ?: return
        val name = activityClass.groupValues[1]
        val declared = Regex("android:name\\s*=\\s*\"([\\w.]+)\"").findAll(manifest.value).map { it.groupValues[1] }.toList()
        if (declared.none { it == name || it.endsWith(".$name") || it == "${project.namespace()}.$name" }) {
            add(
                staticIssue("static.activity-not-in-manifest", ErrorCategory.RUNTIME_RISK, Severity.WARNING, "Activity `$name` is not declared in AndroidManifest.xml.", path, lineOfOffset(activityClass.range.first),
                    suggestion = "Add `<activity android:name=\".$name\" />` inside <application>. Starting an undeclared activity crashes with ActivityNotFoundException.", confidence = Confidence.MEDIUM, symbol = name),
            )
        }
    }
}

// ================================================================================================ XML

internal object XmlChecker {
    /** Reports mismatched or unclosed tags, an unterminated attribute value and stray `&`. */
    fun balance(path: String, text: String): List<Finding> {
        val out = ArrayList<Finding>()
        val stack = ArrayList<Pair<String, Int>>()
        var i = 0
        var line = 1
        val n = text.length
        var roots = 0
        fun err(msg: String, l: Int, sugg: String) {
            out += Finding(staticIssue("static.xml-malformed", ErrorCategory.RESOURCE_LINKING, Severity.ERROR, msg, path, l, suggestion = sugg, confidence = Confidence.HIGH))
        }
        while (i < n) {
            val c = text[i]
            if (c == '\n') { line++; i++; continue }
            if (c != '<') {
                if (c == '&' && stack.isNotEmpty() && !Regex("&(?:#\\d+|#x[0-9a-fA-F]+|[A-Za-z]+);").matchesAt(text, i)) {
                    err("Unescaped `&` in XML text (write `&amp;`).", line, "Replace `&` with `&amp;`.")
                    return out
                }
                i++; continue
            }
            when {
                text.startsWith("<!--", i) -> {
                    val end = text.indexOf("-->", i + 4)
                    if (end < 0) { err("Comment is never closed (missing `-->`).", line, "Close the comment with `-->`."); return out }
                    line += text.substring(i, end).count { it == '\n' }; i = end + 3
                }
                text.startsWith("<![CDATA[", i) -> {
                    val end = text.indexOf("]]>", i)
                    if (end < 0) { err("CDATA section is never closed.", line, "Close it with `]]>`."); return out }
                    line += text.substring(i, end).count { it == '\n' }; i = end + 3
                }
                text.startsWith("<?", i) -> {
                    val end = text.indexOf("?>", i)
                    if (end < 0) { err("XML declaration is never closed.", line, "Close it with `?>`."); return out }
                    i = end + 2
                }
                text.startsWith("<!", i) -> {
                    val end = text.indexOf('>', i)
                    if (end < 0) return out
                    i = end + 1
                }
                text.startsWith("</", i) -> {
                    val end = text.indexOf('>', i)
                    if (end < 0) { err("End tag is never closed.", line, "Add `>`."); return out }
                    val name = text.substring(i + 2, end).trim()
                    val top = stack.lastOrNull()
                    if (top == null) { err("Unexpected end tag `</$name>`.", line, "Remove it, or add the matching start tag."); return out }
                    if (top.first != name) {
                        err("End tag `</$name>` does not match `<${top.first}>` opened on line ${top.second}.", line, "Close `<${top.first}>` first, or fix the tag name.")
                        return out
                    }
                    stack.removeAt(stack.size - 1)
                    i = end + 1
                }
                else -> {
                    var j = i + 1
                    val nameStart = j
                    while (j < n && !text[j].isWhitespace() && text[j] != '>' && text[j] != '/') j++
                    val name = text.substring(nameStart, j)
                    if (name.isEmpty()) { err("A `<` must be followed by a tag name (write `&lt;` for a literal `<`).", line, "Escape it as `&lt;`."); return out }
                    val startLine = line
                    var quote = 0.toChar()
                    var selfClosing = false
                    var closed = false
                    while (j < n) {
                        val ch = text[j]
                        if (ch == '\n') line++
                        if (quote != 0.toChar()) { if (ch == quote) quote = 0.toChar() }
                        else if (ch == '"' || ch == '\'') quote = ch
                        else if (ch == '>') { selfClosing = text[j - 1] == '/'; closed = true; j++; break }
                        else if (ch == '<') break
                        j++
                    }
                    if (!closed) {
                        err(if (quote != 0.toChar()) "Attribute value in `<$name>` is missing its closing quote." else "Tag `<$name>` is never closed (missing `>`).", startLine,
                            "Check the quotes and the closing `>` of this tag.")
                        return out
                    }
                    if (stack.isEmpty()) { roots++; if (roots > 1) { err("More than one root element: `<$name>` follows the end of the document.", startLine, "An XML file has exactly one root element."); return out } }
                    if (!selfClosing) stack.add(name to startLine)
                    i = j
                }
            }
        }
        stack.lastOrNull()?.let { (name, l) -> err("`<$name>` opened here is never closed.", l, "Add `</$name>` (or make it self-closing with `/>`).") }
        return out
    }
}

internal class ResourceIndex(files: Map<String, String>) {
    /** type -> names (with '.' kept as in the XML) */
    val names = HashMap<String, MutableSet<String>>()
    val hasRes: Boolean = files.keys.any { it.contains("/res/") || it.startsWith("res/") }

    private val valueTags = mapOf(
        "string" to "string", "color" to "color", "dimen" to "dimen", "integer" to "integer", "bool" to "bool", "style" to "style",
        "array" to "array", "string-array" to "array", "integer-array" to "array", "plurals" to "plurals", "attr" to "attr",
        "declare-styleable" to "styleable", "drawable" to "drawable", "id" to "id", "fraction" to "fraction",
    )
    private val folderTypes = setOf("layout", "drawable", "mipmap", "menu", "xml", "anim", "animator", "color", "raw", "font", "navigation", "transition", "interpolator")

    init {
        for ((path, text) in files) {
            val p = path.replace('\\', '/')
            val folder = Regex("(?:^|/)res/([a-z]+)(?:-[^/]*)?/[^/]+$").find(p)?.groupValues?.get(1)
            if (folder != null) {
                if (folder == "values") {
                    for (m in Regex("<([a-z-]+)\\s+[^>]*?\\bname\\s*=\\s*\"([^\"]+)\"[^>]*>").findAll(text)) {
                        val tag = m.groupValues[1]
                        val type = if (tag == "item") Regex("\\btype\\s*=\\s*\"([a-z]+)\"").find(m.value)?.groupValues?.get(1) else valueTags[tag]
                        if (type != null) names.getOrPut(type) { HashSet() }.add(m.groupValues[2])
                    }
                } else if (folder in folderTypes) {
                    val base = p.substringAfterLast('/').substringBeforeLast('.').removeSuffix(".9")
                    names.getOrPut(folder) { HashSet() }.add(base)
                    if (folder == "mipmap" || folder == "drawable") names.getOrPut("drawable_or_mipmap") { HashSet() }.add(base)
                }
            }
            if (p.endsWith(".xml")) {
                for (m in Regex("@\\+id/([A-Za-z0-9_.]+)").findAll(text)) names.getOrPut("id") { HashSet() }.add(m.groupValues[1])
            }
        }
    }

    fun has(type: String, name: String): Boolean {
        val set = names[type] ?: return false
        return name in set || name.replace('.', '_') in set || set.any { it.replace('.', '_') == name.replace('.', '_') }
    }

    fun isLibraryName(name: String): Boolean = KnownSymbols.libraryResourcePrefixes.any { name.startsWith(it) }
}

internal object ResourceChecker {
    private val xmlRef = Regex("@(string|color|dimen|drawable|mipmap|layout|style|menu|xml|anim|integer|bool|array|plurals|raw|font|navigation)/([A-Za-z0-9_.]+)")
    private val codeRef = Regex("(?<![\\w.])R\\.(string|color|dimen|drawable|mipmap|layout|style|menu|xml|anim|integer|bool|array|plurals|raw|font|id|navigation)\\.([A-Za-z0-9_]+)")

    private fun exists(index: ResourceIndex, type: String, name: String): Boolean = when (type) {
        "drawable", "mipmap" -> index.has("drawable", name) || index.has("mipmap", name) || index.has("color", name)
        "color" -> index.has("color", name) || index.has("drawable", name)
        else -> index.has(type, name)
    } || index.isLibraryName(name)

    fun checkXml(path: String, text: String, project: ProjectIndex): List<Finding> {
        if (!project.resources.hasRes) return emptyList()
        val out = ArrayList<Finding>()
        val masked = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }
        val idx = SourceText.LineIndex(masked)
        for (m in xmlRef.findAll(masked)) {
            val type = m.groupValues[1]
            val name = m.groupValues[2].trimEnd('.')
            if (exists(project.resources, type, name)) continue
            val line = idx.lineOf(m.range.first)
            out += Finding(
                staticIssue(
                    "static.resource-missing", ErrorCategory.RESOURCE_LINKING, Severity.ERROR, "Resource `@$type/$name` is referenced but not defined in the project.", path, line,
                    suggestion = if (type == "string" || type == "color" || type == "dimen") "Add `<$type name=\"$name\">...</$type>` to app/src/main/res/values/${type}s.xml, or fix the name."
                    else "Create res/$type/$name.xml (or the matching file/value), or fix the name.",
                    confidence = Confidence.MEDIUM, symbol = "$type/$name",
                ),
            )
        }
        return out
    }

    fun checkCodeReferences(files: Map<String, String>, project: ProjectIndex): List<Finding> {
        if (!project.resources.hasRes) return emptyList()
        val out = ArrayList<Finding>()
        for ((path, text) in files) {
            val lower = path.lowercase()
            if (!lower.endsWith(".kt") && !lower.endsWith(".java")) continue
            val masked = SourceText.mask(text, lower.endsWith(".kt"))
            val idx = SourceText.LineIndex(masked)
            val seen = HashSet<String>()
            for (m in codeRef.findAll(masked)) {
                val type = m.groupValues[1]
                val name = m.groupValues[2]
                if (!seen.add("$type.$name")) continue
                val ok = if (type == "id") project.resources.has("id", name) || project.resources.isLibraryName(name) else exists(project.resources, type, name)
                if (ok) continue
                out += Finding(
                    staticIssue(
                        "static.resource-missing", ErrorCategory.RESOURCE_LINKING, if (type == "id") Severity.WARNING else Severity.ERROR,
                        "`R.$type.$name` refers to a resource that is not defined in the project.", path, idx.lineOf(m.range.first),
                        suggestion = "Define the resource (res/${if (type == "id") "layout (android:id=\"@+id/$name\")" else "$type"}) or fix the name. The R class is generated from the resource files.",
                        confidence = Confidence.MEDIUM, symbol = "$type/$name",
                    ),
                )
            }
        }
        return out
    }
}

// ================================================================================================ manifest

internal class ManifestChecker(private val path: String, private val text: String, private val project: ProjectIndex) {
    private val lines = TextEdits.lines(text)
    private val idx = SourceText.LineIndex(text)

    fun run(): List<Finding> {
        val out = ArrayList<Finding>()
        val target = project.targetSdk()
        if (target == null || target >= 31) out += exported()
        out += missingClasses()
        out += internetPermission()
        return out
    }

    private fun exported(): List<Finding> {
        val out = ArrayList<Finding>()
        for (m in Regex("<(activity|activity-alias|service|receiver)\\b([^>]*)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            if (!inner.contains("<intent-filter") || attrs.contains("android:exported")) continue
            val nameMatch = Regex("android:name\\s*=\\s*\"[^\"]*\"").find(attrs) ?: continue
            val offset = m.range.first + 1 + m.groupValues[1].length + nameMatch.range.first + 0
            val line = idx.lineOf(m.range.first)
            val isLauncher = inner.contains("android.intent.action.MAIN")
            val value = if (isLauncher) "true" else "false"
            val issue = staticIssue(
                "static.manifest-exported", ErrorCategory.MANIFEST, Severity.ERROR,
                "<${m.groupValues[1]}> has an intent filter but no android:exported attribute (required when targeting Android 12+).", path, line,
                suggestion = "Add android:exported=\"$value\" ($value because ${if (isLauncher) "it is the launcher entry" else "it should not be started by other apps unless you want that"}).",
                confidence = Confidence.HIGH,
            )
            val nameLineNo = idx.lineOf(m.range.first + 1 + m.groupValues[1].length + nameMatch.range.first)
            val nameLine = lines[nameLineNo - 1]
            val found = nameLine.indexOf(nameMatch.value)
            val fix = if (found >= 0) {
                val indent = nameLine.takeWhile { it == ' ' || it == '\t' }
                val newLines = listOf(nameLine.substring(0, found + nameMatch.value.length).trimEnd('\r'), "$indent${if (found > indent.length) "" else ""}android:exported=\"$value\"" + nameLine.substring(found + nameMatch.value.length).trimEnd('\r'))
                listOf(fixFor(issue, "Add android:exported=\"$value\"", "Adds the attribute on the line after android:name.", path, text, listOf(CodeEdit(nameLineNo, nameLineNo + 1, newLines)), Confidence.HIGH))
            } else emptyList()
            out += Finding(issue, fix)
            if (offset < 0) break
        }
        return out
    }

    private fun missingClasses(): List<Finding> {
        if (!project.hasSources) return emptyList()
        val ns = project.namespace()
        val out = ArrayList<Finding>()
        for (m in Regex("<(activity|service|receiver|provider)\\b[^>]*?android:name\\s*=\\s*\"([^\"]+)\"").findAll(text)) {
            val raw = m.groupValues[2]
            val simple = raw.substringAfterLast('.')
            if (raw.startsWith("android.") || raw.startsWith("androidx.") || raw.startsWith("com.google.") || raw.startsWith("com.android.")) continue
            val fqns = project.types[simple]
            val expected = when {
                raw.startsWith(".") -> ns?.let { it + raw }
                !raw.contains('.') -> ns?.let { "$it.$raw" }
                else -> raw
            }
            if (fqns == null || (expected != null && expected !in fqns && fqns.none { it.endsWith(".$simple") && expected.substringBeforeLast('.') == it.substringBeforeLast('.') })) {
                out += Finding(
                    staticIssue("static.manifest-class-missing", ErrorCategory.MANIFEST, Severity.WARNING,
                        "<${m.groupValues[1]}> `$raw` was not found in the project sources.", path, idx.lineOf(m.range.first),
                        suggestion = "Check the android:name (a leading `.` is relative to the namespace${ns?.let { " `$it`" } ?: ""}) and the class's package. A missing class crashes the app at start.",
                        confidence = Confidence.MEDIUM, symbol = simple),
                )
            }
        }
        return out
    }

    private fun internetPermission(): List<Finding> {
        if (text.contains("android.permission.INTERNET")) return emptyList()
        val usesNetwork = project.files.any { (p, t) ->
            (p.endsWith(".kt") || p.endsWith(".java")) && Regex("\\bOkHttpClient\\b|\\bHttpURLConnection\\b|\\bURL\\s*\\(|\\bWebView\\b|\\bHttpClient\\b|\\.openConnection\\(").containsMatchIn(SourceText.mask(t, p.endsWith(".kt")))
        }
        if (!usesNetwork) return emptyList()
        val tag = Regex("<manifest\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).find(text) ?: return emptyList()
        val tagEndLine = idx.lineOf(tag.range.last)
        val issue = staticIssue(
            "static.manifest-internet", ErrorCategory.RUNTIME_RISK, Severity.WARNING,
            "The code uses the network but the manifest has no INTERNET permission.", path, tagEndLine,
            suggestion = "Add <uses-permission android:name=\"android.permission.INTERNET\" /> inside <manifest>. Without it, every connection fails at run time (the build still succeeds).",
            confidence = Confidence.MEDIUM,
        )
        val indent = "    "
        val fix = fixFor(issue, "Add INTERNET permission", "Inserts the permission after the <manifest> tag.", path, text,
            listOf(CodeEdit.insert(tagEndLine + 1, listOf("${indent}<uses-permission android:name=\"android.permission.INTERNET\" />"))))
        return listOf(Finding(issue, listOf(fix)))
    }
}

// ================================================================================================ gradle

internal class GradleChecker(private val path: String, private val text: String, private val project: ProjectIndex) {
    private val kts = path.endsWith(".kts")
    private val idx = SourceText.LineIndex(text)
    private val lines = TextEdits.lines(text)

    private fun issue(rule: String, sev: Severity, msg: String, line: Int?, sugg: String, conf: Confidence = Confidence.MEDIUM) =
        staticIssue(rule, ErrorCategory.GRADLE, sev, msg, path, line, suggestion = sugg, confidence = conf)

    fun run(): List<Finding> {
        val out = ArrayList<Finding>()
        val masked = SourceText.mask(text, kotlin = true)
        SourceText.bracketProblems(masked, 1).forEach {
            out += Finding(issue("static.brackets", Severity.ERROR, it.message, it.line, "Check the brackets around this line.", Confidence.HIGH))
        }
        val androidBlock = Regex("(?m)^\\s*android\\s*\\{").find(masked) ?: return out
        val androidLine = idx.lineOf(androidBlock.range.first)
        if (!Regex("\\bnamespace\\b").containsMatchIn(masked)) {
            val ns = Regex("applicationId\\s*(?:=)?\\s*[\"']([\\w.]+)[\"']").find(text)?.groupValues?.get(1) ?: project.namespace()
            val i = issue("static.gradle-namespace", Severity.ERROR, "No `namespace` in the android { } block (required by Android Gradle plugin 8+).", androidLine,
                "Add namespace = \"your.package\" inside android { }.", Confidence.HIGH)
            val fix = ns?.let {
                val line = if (kts) "    namespace = \"$it\"" else "    namespace \"$it\""
                listOf(fixFor(i, "Add namespace \"$it\"", "Inserts the namespace line inside android { }.", path, text, listOf(CodeEdit.insert(androidLine + 1, listOf(line))), Confidence.MEDIUM))
            }.orEmpty()
            out += Finding(i, fix)
        }
        val compile = Regex("compileSdk(?:Version)?\\s*(?:=)?\\s*(?:release\\()?(\\d+)").find(masked)?.groupValues?.get(1)?.toIntOrNull()
        val target = Regex("targetSdk(?:Version)?\\s*(?:=)?\\s*(\\d+)").find(masked)?.groupValues?.get(1)?.toIntOrNull()
        val min = Regex("minSdk(?:Version)?\\s*(?:=)?\\s*(\\d+)").find(masked)?.groupValues?.get(1)?.toIntOrNull()
        if (compile == null && !Regex("compileSdk").containsMatchIn(masked)) {
            out += Finding(issue("static.gradle-compilesdk", Severity.ERROR, "compileSdk is not set.", androidLine, "Add compileSdk = 35 (or the newest installed platform) inside android { }.", Confidence.HIGH))
        }
        if (compile != null && target != null && target > compile) {
            out += Finding(issue("static.gradle-target-gt-compile", Severity.WARNING, "targetSdk ($target) is higher than compileSdk ($compile).", lineOfFirst("targetSdk"),
                "Raise compileSdk to at least $target, or lower targetSdk."))
        }
        if (min != null && target != null && min > target) {
            out += Finding(issue("static.gradle-min-gt-target", Severity.WARNING, "minSdk ($min) is higher than targetSdk ($target).", lineOfFirst("minSdk"),
                "minSdk must not be above targetSdk."))
        }
        if (!Regex("platform\\(|bom").containsMatchIn(masked)) {
            for (m in Regex("(?m)^\\s*(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|kapt|ksp)\\s*\\(?\\s*[\"']([^\"':\\s]+:[^\"':\\s]+)[\"']\\s*\\)?\\s*$").findAll(text)) {
                out += Finding(issue("static.gradle-dependency-no-version", Severity.WARNING, "Dependency `${m.groupValues[1]}` has no version.", idx.lineOf(m.range.first + m.value.length - m.value.trimStart().length),
                    "Write group:name:version, or use a BOM (platform(...)) or the version catalog. Without a version Gradle cannot resolve it."))
            }
        }
        return out
    }

    private fun lineOfFirst(word: String): Int? = lines.indexOfFirst { it.contains(word) }.takeIf { it >= 0 }?.plus(1)
}

// ================================================================================================ project level

internal object ProjectChecks {
    fun run(project: ProjectIndex): List<Finding> {
        val out = ArrayList<Finding>()
        val files = project.files.keys
        val hasAndroidApp = project.files.any { (p, t) -> (p.endsWith("build.gradle.kts") || p.endsWith("build.gradle")) && t.contains("com.android.application") || t.contains("android.application") }
        if (hasAndroidApp && files.none { it.endsWith("AndroidManifest.xml") }) {
            out += Finding(staticIssue("static.no-manifest", ErrorCategory.MANIFEST, Severity.ERROR, "The project has an Android application module but no AndroidManifest.xml.", "app/src/main/AndroidManifest.xml", null,
                suggestion = "Create app/src/main/AndroidManifest.xml with an <application> and a launcher <activity>.", confidence = Confidence.HIGH))
        }
        if (hasAndroidApp && files.none { it == "settings.gradle.kts" || it == "settings.gradle" }) {
            out += Finding(staticIssue("static.no-settings", ErrorCategory.GRADLE, Severity.ERROR, "No settings.gradle(.kts) found.", "settings.gradle.kts", null,
                suggestion = "Gradle needs settings.gradle(.kts) with rootProject.name and include(\":app\").", confidence = Confidence.HIGH))
        }
        return out
    }
}
