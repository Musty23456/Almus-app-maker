package com.almus.appcreatorpro.debug

/**
 * Phase 22: the diagnosis engine. It turns a raw build log into a [Diagnosis]:
 *
 *  1. [BuildErrorAnalyzer] finds what compilers and Gradle report (unchanged, Phase 8).
 *  2. [LogSignals] adds what the analyzer does not know (GitHub Actions, network kinds, dependency conflicts).
 *  3. [StackTraceAnalyzer] reads stack traces; a trace that nothing else explains becomes an issue of its own.
 *  4. Every issue is classified ([DiagnosisCategory]), repeated problems are merged, and each remaining error is
 *     either a root cause or - with the evidence named - a secondary error that follows from one.
 *  5. Severity, affected files and lines, a "why" and a confidence for the suggested solution are worked out.
 *
 * Principles that the tests pin down:
 *  - Only evidence from the log, the stack traces and the project's file list is used.
 *  - A link "this error follows from that one" is made only by a rule that names its evidence, and carries its own
 *    confidence. Where two errors might be related but nothing proves it, they stay separate and a limitation is added.
 *  - File locations that are only inferred are labelled as inferred.
 *  - Generic or unrecognised messages get a low confidence and are marked as having insufficient evidence.
 *
 * Plain Kotlin, no Android classes. The cost is linear in the log size except the linking step, which works on at most
 * [BuildErrorAnalyzer]'s error cap (300) issues.
 */
class DiagnosisEngine(
    private val resolver: ProjectPathResolver? = null,
    projectFiles: Collection<String> = emptyList(),
) {
    private val files: List<String> = projectFiles.map { it.replace('\\', '/').trimStart('/') }

    /** [analysis] has the enriched issue list (analyzer + signals + stack traces); [diagnosis] explains it. */
    class Run(val analysis: AnalysisResult, val diagnosis: Diagnosis)

    /** Analyses [rawLog]. [truncated] says that only the end of a longer log was given (see [LogLimits]). */
    fun run(rawLog: String, truncated: Boolean = false): Run {
        val base = BuildErrorAnalyzer(resolver).analyze(rawLog)
        val lines = LogCleaner.lines(rawLog)
        return diagnose(base, lines, truncated)
    }

    /** Same as [run] for a log that has already been analysed and cleaned (so each step is done once). */
    fun diagnose(base: AnalysisResult, lines: List<LogCleaner.Line>, truncated: Boolean = false): Run {
        val info = HashMap<String, SignalInfo>()
        val traceOf = HashMap<String, StackTraceAnalysis>()

        // ---- 1. extra signals and stack traces -------------------------------------------------------------------
        val signals = LogSignals.scan(lines, resolver, base.issues)
        info += signals.infoById
        var issues: List<BuildIssue> = base.issues.filter { it.id !in signals.replacedIssueIds } + signals.issues

        val traces = StackTraceAnalyzer(resolver).analyze(lines)
        val extraFromTraces = ArrayList<BuildIssue>()
        for (t in traces) {
            val owner = attachTrace(t, issues)
            if (owner != null) { if (owner.id !in traceOf) traceOf[owner.id] = t; continue }
            val made = issueFromTrace(t, issues) ?: continue
            extraFromTraces += made.first
            info[made.first.id] = made.second
            traceOf[made.first.id] = t
        }
        issues = issues + extraFromTraces

        val markers = LogSignals.unexplainedErrorMarkers(lines, issues)
        info += markers.infoById
        issues = issues + markers.issues

        // The analyzer's "build failed but nothing recognised" placeholder is replaced by anything real we found.
        val foundReal = signals.issues.any { it.severity == Severity.ERROR } || extraFromTraces.isNotEmpty() || markers.issues.isNotEmpty()
        if (foundReal) issues = issues.filterNot { isPlaceholder(it) }
        issues = issues.sortedWith(compareBy<BuildIssue> { it.severity.rank }.thenBy { it.logIndex ?: Int.MAX_VALUE })

        val analysis = AnalysisResult(issues, base.buildFailed, base.omittedWarnings)
        return Run(analysis, buildDiagnosis(analysis, info, traceOf, traces, truncated))
    }

    /** Diagnoses an existing analysis without a stack-trace / signal pass (used when only [BuildIssue]s are at hand). */
    fun diagnoseIssues(analysis: AnalysisResult, truncated: Boolean = false): Diagnosis =
        buildDiagnosis(analysis, emptyMap(), emptyMap(), emptyList(), truncated)

    // ---------------------------------------------------------------------------------------------------------------
    // stack traces
    // ---------------------------------------------------------------------------------------------------------------

    private fun attachTrace(trace: StackTraceAnalysis, issues: List<BuildIssue>): BuildIssue? {
        val errors = issues.filter { it.severity == Severity.ERROR && it.logIndex != null }
        val texts = trace.chain.mapNotNull { it.message }
        // 1. an issue whose symbol / message is quoted by the exception
        errors.firstOrNull { i ->
            val sym = i.symbol
            val near = i.logIndex!! in (trace.startLogIndex - TRACE_WINDOW)..(trace.endLogIndex + 2)
            near && ((sym != null && sym.length >= 4 && texts.any { it.contains(sym) }) ||
                texts.any { t -> t.length >= 20 && i.message.contains(t.take(40)) })
        }?.let { return it }
        // 2. the closest error just before the trace ("* What went wrong" is printed before "Exception is:")
        return errors.filter { it.logIndex!! <= trace.startLogIndex && trace.startLogIndex - it.logIndex!! <= TRACE_WINDOW }
            .maxByOrNull { it.logIndex!! }
    }

    private fun issueFromTrace(trace: StackTraceAnalysis, existing: List<BuildIssue>): Pair<BuildIssue, SignalInfo>? {
        val root = trace.rootCause
        val knowledge = ExceptionKnowledge.forType(root.simpleName)
        val hasRealError = existing.any { it.severity == Severity.ERROR && !isPlaceholder(it) && !it.rule.startsWith("summary.") }
        // An unknown exception type next to errors that are already reported is noise; a known one is worth its own entry.
        if (knowledge == null && hasRealError) return null
        val net = if (knowledge?.category == DiagnosisCategory.NETWORK) NetworkInterpreter.interpret((root.message ?: "") + " " + root.type) else null
        val frame = trace.firstProjectFrame
        val message = LogCleaner.redact(root.type + (root.message?.let { ": $it" } ?: "")).take(400)
        val whyText = net?.why ?: knowledge?.why
            ?: "The log contains a stack trace for ${root.type}. The exception type alone does not say why it happened; the message and the first frame in your code are the evidence."
        val solution = net?.solution ?: knowledge?.solution
        val category = net?.category ?: knowledge?.category ?: DiagnosisCategory.JVM_EXCEPTION
        val scope = knowledge?.scope ?: FixScope.UNKNOWN
        val errorCategory = when (category) {
            DiagnosisCategory.NETWORK -> ErrorCategory.NETWORK
            DiagnosisCategory.DEPENDENCY_CONFLICT, DiagnosisCategory.DEPENDENCY_RESOLUTION -> ErrorCategory.MISSING_DEPENDENCY
            DiagnosisCategory.ANDROID_SDK -> ErrorCategory.SDK_CONFIGURATION
            DiagnosisCategory.GRADLE -> ErrorCategory.GRADLE
            else -> ErrorCategory.OTHER
        }
        val issue = BuildIssue(
            id = Fingerprint.of("jvm.exception", trace.id), rule = "jvm.exception", category = errorCategory, severity = Severity.ERROR,
            message = message, file = frame?.file, projectPath = frame?.projectPath, line = frame?.line, column = null,
            suggestion = solution, source = IssueSource.BUILD_LOG, confidence = if (knowledge != null) Confidence.HIGH else Confidence.MEDIUM,
            symbol = root.simpleName, context = listOf(message) + (frame?.let { listOf("at ${it.text}") } ?: emptyList()),
            logIndex = trace.startLogIndex,
        )
        return issue to SignalInfo(category, scope, whyText)
    }

    private fun isPlaceholder(i: BuildIssue) = i.rule == "gradle.unrecognised" && i.file == null && i.message.startsWith("The build failed, but no specific error")

    // ---------------------------------------------------------------------------------------------------------------
    // diagnosis
    // ---------------------------------------------------------------------------------------------------------------

    private class Work(
        val issue: BuildIssue,
        val category: DiagnosisCategory,
        val scope: FixScope,
        val why: String?,
        val solution: String?,
        val trace: StackTraceAnalysis?,
        val net: NetworkFinding?,
    ) {
        var link: CausalLink? = null
        val merged = ArrayList<BuildIssue>()
        var occurrences = 1
        val extraFiles = ArrayList<AffectedFile>()

        /** An error that is not just a restatement ("Execution failed for task ...") of other lines. */
        val isRealError: Boolean get() = issue.severity == Severity.ERROR && !issue.rule.startsWith("summary.")
        val idx: Int get() = issue.logIndex ?: Int.MAX_VALUE
        val fileKey: String? get() = issue.projectPath ?: issue.file
    }

    private fun buildDiagnosis(
        analysis: AnalysisResult,
        info: Map<String, SignalInfo>,
        traceOf: Map<String, StackTraceAnalysis>,
        traces: List<StackTraceAnalysis>,
        truncated: Boolean,
    ): Diagnosis {
        val limitations = ArrayList<String>()
        var works = analysis.issues.map { classify(it, info[it.id], traceOf[it.id]) }

        // ---- merge repeated problems -------------------------------------------------------------------------------
        works = mergeRepeated(works)

        // ---- cause and effect --------------------------------------------------------------------------------------
        val unmatchedDependencyNote = link(works)

        // ---- assemble ----------------------------------------------------------------------------------------------
        val hasRoot = works.any { it.isRealError && it.link == null }
        val byId = works.associateBy { it.issue.id }
        fun rootOf(w: Work): Work {
            var cur = w
            var guard = 0
            while (cur.link != null && guard++ < 10) cur = byId[cur.link!!.rootIssueId] ?: break
            return cur
        }
        // links must end at a root: re-point chains
        for (w in works) {
            val l = w.link ?: continue
            val r = rootOf(w)
            if (r.issue.id != l.rootIssueId) w.link = CausalLink(r.issue.id, l.reason, weaker(l.confidence, Confidence.MEDIUM))
        }
        val secondaryByRoot = works.filter { it.link != null }.groupBy { it.link!!.rootIssueId }.mapValues { e -> e.value.map { it.issue.id } }

        val diagnosed = works.map { w ->
            val role = when {
                w.link != null -> ErrorRole.SECONDARY
                w.isRealError -> ErrorRole.ROOT_CAUSE
                isPlaceholder(w.issue) || (w.issue.severity == Severity.ERROR && !hasRoot) -> ErrorRole.ROOT_CAUSE
                else -> ErrorRole.INFORMATIONAL
            }
            val files = affectedFilesOf(w)
            val confidence = confidenceOf(w, role, truncated)
            DiagnosedIssue(
                issue = w.issue, category = w.category, role = role, severity = severityOf(w, role), scope = w.scope, link = w.link,
                affectedFiles = files, why = w.why, solution = w.solution, confidence = confidence, stackTrace = w.trace,
                occurrences = w.occurrences, mergedIssueIds = w.merged.map { it.id }, secondaryIds = secondaryByRoot[w.issue.id].orEmpty(),
            )
        }.sortedWith(
            compareBy<DiagnosedIssue> { roleOrder(it.role) }.thenBy { it.severity.rank }.thenBy { it.issue.logIndex ?: Int.MAX_VALUE },
        )

        // ---- limitations: what this analysis could NOT tell ----------------------------------------------------------
        if (truncated) limitations += "Only the end of a long log was analysed, so an earlier error may be missing from this diagnosis."
        val roots = diagnosed.filter { it.role == ErrorRole.ROOT_CAUSE }
        if (roots.size > 1 && roots.count { it.severity == SeverityLevel.CRITICAL } > 1) limitations += "There are ${roots.size} separate root causes. Fixing one of them will not make the build pass; each needs its own fix."
        unmatchedDependencyNote?.let { limitations += it }
        val outside = diagnosed.count { it.role != ErrorRole.INFORMATIONAL && it.issue.file != null && it.issue.projectPath == null && it.stackTrace == null }
        if (outside > 0 && resolver != null) limitations += "$outside error(s) name a file that could not be matched to exactly one file of this project (a CI log prints the build machine's paths), so no line of your project is named for them."
        if (analysis.omittedWarnings > 0) limitations += "${analysis.omittedWarnings} further problem(s) were left out because the log contains very many."
        if (analysis.buildFailed == null && diagnosed.any { it.role == ErrorRole.ROOT_CAUSE }) limitations += "The log does not say whether the build failed or succeeded."
        for (t in traces) if (t.frameworkOnly && traceOf.values.any { it.id == t.id }) {
            limitations += "A stack trace (${t.rootCause.simpleName}) has no frame from your project, so it shows where a library or tool failed, not which line of your code started it."
            break
        }
        if (diagnosed.isNotEmpty() && roots.isNotEmpty() && roots.all { !it.confidence.sufficient }) limitations += "The log does not contain enough information to name a specific cause. The suggestions are general guidance only."

        return Diagnosis(diagnosed, traces, analysis.buildFailed, limitations)
    }

    private fun roleOrder(r: ErrorRole) = when (r) { ErrorRole.ROOT_CAUSE -> 0; ErrorRole.SECONDARY -> 1; ErrorRole.INFORMATIONAL -> 2 }
    private fun weaker(a: Confidence, b: Confidence) = if (a.ordinal >= b.ordinal) a else b

    // ---- classification --------------------------------------------------------------------------------------------

    private fun classify(issue: BuildIssue, info: SignalInfo?, trace: StackTraceAnalysis?): Work {
        val net = if (issue.rule == "dependency.network") NetworkInterpreter.interpret(issue.message + " " + issue.context.joinToString(" ")) else null
        val category = info?.category ?: net?.category ?: categoryOfRule(issue)
        val scope = info?.scope ?: net?.let { scopeOfNetwork(it) } ?: scopeOfRule(issue, category)
        val why = info?.why ?: net?.why ?: ErrorExplanations.why(issue) ?: knowledgeWhy(issue, trace)
        val solution = net?.solution ?: issue.suggestion ?: knowledgeSolution(trace)
        return Work(issue, category, scope, why, solution, trace, net)
    }

    private fun knowledgeWhy(issue: BuildIssue, trace: StackTraceAnalysis?): String? {
        if (trace == null) return null
        return ExceptionKnowledge.forType(trace.rootCause.simpleName)?.why
    }

    private fun knowledgeSolution(trace: StackTraceAnalysis?): String? = trace?.let { ExceptionKnowledge.forType(it.rootCause.simpleName)?.solution }

    private fun categoryOfRule(issue: BuildIssue): DiagnosisCategory {
        val rule = issue.rule
        if (issue.source == IssueSource.STATIC_ANALYSIS) return DiagnosisCategory.STATIC_CHECK
        return when {
            rule == "kotlin.binary-incompatible" -> DiagnosisCategory.DEPENDENCY_CONFLICT
            rule == "java.invalid-release" -> DiagnosisCategory.ANDROID_SDK
            rule == "resource.xml-parse" -> DiagnosisCategory.XML
            rule == "dependency.duplicate-class" || rule == "dependency.no-variant" -> DiagnosisCategory.DEPENDENCY_CONFLICT
            rule == "dependency.network" -> DiagnosisCategory.NETWORK
            rule.startsWith("dependency.") -> DiagnosisCategory.DEPENDENCY_RESOLUTION
            else -> when (issue.category) {
                ErrorCategory.KOTLIN_COMPILATION -> DiagnosisCategory.KOTLIN_COMPILATION
                ErrorCategory.JAVA_COMPILATION -> DiagnosisCategory.JAVA_COMPILATION
                ErrorCategory.GRADLE -> DiagnosisCategory.GRADLE
                ErrorCategory.MISSING_DEPENDENCY -> DiagnosisCategory.DEPENDENCY_RESOLUTION
                ErrorCategory.MANIFEST -> DiagnosisCategory.MANIFEST
                ErrorCategory.RESOURCE_LINKING -> DiagnosisCategory.RESOURCE_LINKING
                ErrorCategory.SDK_CONFIGURATION -> DiagnosisCategory.ANDROID_SDK
                ErrorCategory.NETWORK -> DiagnosisCategory.NETWORK
                ErrorCategory.GITHUB_ACTIONS -> DiagnosisCategory.GITHUB_ACTIONS
                ErrorCategory.CODE_QUALITY, ErrorCategory.RUNTIME_RISK -> DiagnosisCategory.STATIC_CHECK
                ErrorCategory.OTHER -> DiagnosisCategory.UNKNOWN
            }
        }
    }

    private fun scopeOfNetwork(n: NetworkFinding): FixScope = when (n.kind) {
        NetworkKind.HTTP_NOT_FOUND, NetworkKind.HTTP_FORBIDDEN -> FixScope.PROJECT_CONFIG
        else -> FixScope.ENVIRONMENT
    }

    private fun scopeOfRule(issue: BuildIssue, category: DiagnosisCategory): FixScope = when {
        issue.rule in ENVIRONMENT_RULES -> FixScope.ENVIRONMENT
        else -> when (category) {
            DiagnosisCategory.KOTLIN_COMPILATION, DiagnosisCategory.JAVA_COMPILATION, DiagnosisCategory.MANIFEST,
            DiagnosisCategory.XML, DiagnosisCategory.RESOURCE_LINKING -> FixScope.PROJECT_CODE
            DiagnosisCategory.GRADLE, DiagnosisCategory.ANDROID_SDK, DiagnosisCategory.DEPENDENCY_CONFLICT,
            DiagnosisCategory.DEPENDENCY_RESOLUTION, DiagnosisCategory.GITHUB_ACTIONS -> FixScope.PROJECT_CONFIG
            DiagnosisCategory.NETWORK -> FixScope.ENVIRONMENT
            DiagnosisCategory.STATIC_CHECK -> FixScope.PROJECT_CODE
            DiagnosisCategory.JVM_EXCEPTION, DiagnosisCategory.UNKNOWN -> FixScope.UNKNOWN
        }
    }

    // ---- repeated problems ---------------------------------------------------------------------------------------

    private fun mergeRepeated(works: List<Work>): List<Work> {
        val groups = LinkedHashMap<Pair<String, String>, ArrayList<Work>>()
        for (w in works) {
            val sym = w.issue.symbol ?: continue
            if (w.issue.rule !in MERGEABLE_RULES || w.issue.severity != Severity.ERROR) continue
            groups.getOrPut(w.issue.rule to sym) { ArrayList() } += w
        }
        val drop = HashSet<String>()
        for ((_, members) in groups) {
            if (members.size < 2) continue
            val first = members.minByOrNull { it.idx } ?: continue
            for (m in members) {
                if (m === first) continue
                drop += m.issue.id
                first.merged += m.issue
                m.issue.file?.let { f ->
                    if (first.extraFiles.size < MAX_AFFECTED - 1) first.extraFiles += AffectedFile(m.issue.projectPath ?: f, m.issue.projectPath != null, m.issue.line, m.issue.column, LocationBasis.LOG_LOCATION)
                }
            }
            first.occurrences = members.size
        }
        return works.filter { it.issue.id !in drop }
    }

    // ---- cause and effect ----------------------------------------------------------------------------------------

    /** Sets [Work.link] where a rule has evidence. Returns a limitation text for related-looking errors that could not be linked. */
    private fun link(works: List<Work>): String? {
        val errors = works.filter { it.isRealError }
        if (errors.size < 2) return null

        // R1: a failed download explains the "could not resolve" it is printed under.
        val net = errors.filter { it.category == DiagnosisCategory.NETWORK }
        val resolution = errors.filter { it.issue.rule in RESOLUTION_RULES }
        for (d in resolution) {
            val near = net.filter { kotlin.math.abs(it.idx - d.idx) <= NEAR_LINES }.minByOrNull { kotlin.math.abs(it.idx - d.idx) }
            if (near != null) {
                d.link = CausalLink(near.issue.id, "The download failure (${near.net?.kind?.label ?: "network error"}) is printed right next to this dependency error: the dependency could not be resolved because it could not be downloaded.", Confidence.HIGH)
                continue
            }
            val sameTask = net.firstOrNull { it.issue.task != null && it.issue.task == d.issue.task && it.idx < d.idx }
            if (sameTask != null) {
                d.link = CausalLink(sameTask.issue.id, "A network error occurred earlier in the same Gradle task (${d.issue.task}); the dependency may have failed to resolve because of it, but the log does not connect the two lines directly.", Confidence.MEDIUM)
            }
        }
        // R1b: an HTTP 404 from a repository is the detail of the missing dependency next to it, not a separate problem.
        for (n in errors.filter { it.issue.rule == "dependency.network" && it.category == DiagnosisCategory.DEPENDENCY_RESOLUTION }) {
            val owner = resolution.filter { kotlin.math.abs(it.idx - n.idx) <= NEAR_LINES && it.link == null }.minByOrNull { kotlin.math.abs(it.idx - n.idx) }
            if (owner != null) n.link = CausalLink(owner.issue.id, "This is the HTTP answer (404) behind the missing dependency printed next to it.", Confidence.HIGH)
        }

        // R2: R / view binding classes are generated from resources.
        val resourceRoots = errors.filter { it.link == null && it.category in RESOURCE_CATEGORIES }.sortedBy { it.idx }
        if (resourceRoots.isNotEmpty()) {
            val root = resourceRoots.first()
            for (e in errors) {
                if (e.link != null || e.category !in CODE_CATEGORIES || !isGeneratedResourceSymbol(e)) continue
                e.link = CausalLink(root.issue.id, "`${e.issue.symbol}` is generated from the app's resources. The resource error (${root.issue.rule}) is in the same log, and while it exists the generated classes cannot be created.", Confidence.MEDIUM)
            }
        }

        // R3: a missing library explains unresolved names that belong to it, when the names match.
        val libs = errors.filter { it.issue.rule in LIBRARY_RULES && it.issue.symbol != null }
        var unmatched = 0
        val candidates = errors.filter { it.link == null && it.issue.rule in UNRESOLVED_RULES }
        if (libs.isNotEmpty()) {
            for (e in candidates) {
                val hit = libs.firstOrNull { matchesLibrary(e.issue.symbol, it.issue.symbol!!) }
                if (hit != null) e.link = CausalLink(hit.issue.id, "`${e.issue.symbol}` looks like it belongs to the library `${hit.issue.symbol}`, which could not be resolved, so its classes are not on the class path.", Confidence.MEDIUM)
                else unmatched++
            }
        }

        // R4: a syntax error makes the parser report follow-up errors later in the same file.
        val syntax = errors.filter { it.link == null && (it.issue.rule == "kotlin.syntax" || it.issue.rule == "java.syntax") && it.issue.line != null && it.fileKey != null }
        for (e in errors) {
            if (e.link != null || e.issue.line == null || e.fileKey == null || e.issue.rule == "kotlin.syntax" || e.issue.rule == "java.syntax") continue
            val s = syntax.filter { it.fileKey == e.fileKey && it.issue.line!! < e.issue.line!! }.minByOrNull { it.issue.line!! } ?: continue
            e.link = CausalLink(s.issue.id, "This error comes after a syntax error in the same file (line ${s.issue.line}). A parser that loses track of the structure reports later lines wrongly, so fix the syntax error first and build again.", Confidence.MEDIUM)
        }

        return if (unmatched > 0 && libs.isNotEmpty()) "$unmatched unresolved name(s) could not be tied to the dependency that failed to resolve. They may or may not be caused by it: the log does not say."
        else null
    }

    private fun isGeneratedResourceSymbol(w: Work): Boolean {
        val s = w.issue.symbol ?: return false
        if (s == "BuildConfig") return false
        return s == "R" || s.startsWith("R.") || s.endsWith(".R") || (s.endsWith("Binding") && s.length > "Binding".length)
    }

    private fun matchesLibrary(symbol: String?, coordinate: String): Boolean {
        if (symbol == null) return false
        val parts = coordinate.split(':')
        if (parts.size < 2) return false
        val groupLast = parts[0].substringAfterLast('.').lowercase()
        val name = parts[1].lowercase()
        val segments = symbol.lowercase().split('.', '/', ':', '$').filter { it.isNotEmpty() }
        return segments.any { seg -> (seg == name && name.length >= 4 && name !in GENERIC_NAMES) || (seg == groupLast && groupLast.length >= 4 && groupLast !in GENERIC_NAMES) }
    }

    // ---- severity ---------------------------------------------------------------------------------------------------

    private fun severityOf(w: Work, role: ErrorRole): SeverityLevel {
        val i = w.issue
        return when {
            i.severity == Severity.INFO -> SeverityLevel.INFO
            i.severity == Severity.WARNING ->
                if (i.rule in FUTURE_BREAKING_WARNINGS || i.message.contains("[removal]")) SeverityLevel.MEDIUM else SeverityLevel.LOW
            role == ErrorRole.ROOT_CAUSE -> SeverityLevel.CRITICAL
            role == ErrorRole.SECONDARY -> SeverityLevel.HIGH
            else -> SeverityLevel.INFO // an error line that only restates another one
        }
    }

    // ---- affected files ---------------------------------------------------------------------------------------------

    private fun affectedFilesOf(w: Work): List<AffectedFile> {
        val out = ArrayList<AffectedFile>()
        val i = w.issue
        val named = i.projectPath ?: i.file?.removePrefix("file://")
        if (named != null) {
            val inProject = i.projectPath != null
            out += AffectedFile(
                named, inProject, i.line, i.column, LocationBasis.LOG_LOCATION,
                note = if (!inProject) "Not found in your project (a library, a generated file or the build machine's own path)." else null,
            )
        }
        out += w.extraFiles
        w.trace?.projectFrames?.take(MAX_TRACE_FILES)?.forEach { f ->
            val path = f.projectPath ?: return@forEach
            if (out.none { it.path == path && it.line == f.line }) out += AffectedFile(path, true, f.line, null, LocationBasis.STACK_TRACE, "${f.declaringClass.substringAfterLast('.')}.${f.method}")
        }
        if (out.none { it.inProject }) {
            for (path in inferredFilesFor(w)) out += AffectedFile(path.first, true, null, null, LocationBasis.INFERRED, path.second)
        }
        return out.distinctBy { Triple(it.path, it.line, it.basis) }.take(MAX_AFFECTED)
    }

    /** Project files that are probably involved when the log names none. Only files that really exist in the project are returned. */
    private fun inferredFilesFor(w: Work): List<Pair<String, String>> {
        if (files.isEmpty()) return emptyList()
        val rule = w.issue.rule
        fun first(vararg candidates: String) = candidates.firstOrNull { it in files }
        val app = first("app/build.gradle.kts", "app/build.gradle")
        val settings = first("settings.gradle.kts", "settings.gradle")
        val root = first("build.gradle.kts", "build.gradle")
        val out = ArrayList<Pair<String, String>>()
        when {
            rule == "dependency.plugin-missing" -> { settings?.let { out += it to "plugin repositories are declared here" }; root?.let { out += it to "plugins are declared here" } }
            rule in RESOLUTION_RULES -> { app?.let { out += it to "dependencies are declared here" }; settings?.let { out += it to "repositories are declared here" } }
            w.category == DiagnosisCategory.DEPENDENCY_CONFLICT -> app?.let { out += it to "dependencies and packaging are declared here" }
            rule.startsWith("sdk.compile") || rule.startsWith("sdk.min") || rule == "gradle.namespace" || rule == "gradle.jvm-target" || rule == "gradle.dex-limit" ->
                app?.let { out += it to "SDK versions and module options are set here" }
            w.category == DiagnosisCategory.MANIFEST -> first("app/src/main/AndroidManifest.xml")?.let { out += it to "the app manifest" }
            w.category == DiagnosisCategory.GITHUB_ACTIONS && !rule.startsWith("gha.workflow") -> {
                val wf = files.filter { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) }
                if (wf.size == 1) out += wf.first() to "the only workflow file of the project"
            }
        }
        return out
    }

    // ---- confidence -------------------------------------------------------------------------------------------------

    private fun confidenceOf(w: Work, role: ErrorRole, truncated: Boolean): DiagnosticConfidence {
        val i = w.issue
        val reasons = ArrayList<String>()
        var score = 40
        val specific = isSpecificRule(i.rule)
        if (specific) { score += 25; reasons += "The message matches a known error pattern (${i.rule})." }
        else reasons += "The message does not match a known error pattern, so the suggestion is general guidance, not a diagnosis."
        when {
            i.file != null && i.line != null -> { score += 10; reasons += "The log names the file and line." }
            i.file != null -> { score += 5; reasons += "The log names the file but not the line." }
            w.trace?.firstProjectFrame != null -> { score += 5; reasons += "A stack trace names a line of your code." }
            else -> reasons += "The log does not name a file."
        }
        if (i.projectPath != null) { score += 5; reasons += "The file was found in your project." }
        else if (i.file != null) reasons += "The named file was not found in your project, so the line cannot be checked against your code."
        if (i.symbol != null) score += 5
        if (w.occurrences > 1) { score += 5; reasons += "The same problem was reported ${w.occurrences} times." }
        if (w.link?.confidence == Confidence.HIGH) { score += 5; reasons += "The log itself shows how this error follows from the root cause." }
        if (w.link?.confidence == Confidence.MEDIUM) reasons += "That this error follows from the root cause is likely, not proven."
        if (w.net?.kind?.transient == true) reasons += "Network problems are often temporary; running the build again may be all that is needed, but the log cannot show whether the cause is gone."
        val cap = CONFIDENCE_CAPS[i.rule] ?: if (w.net?.kind?.transient == true) 70 else 90
        if (score > cap) { score = cap; if (cap < 90) reasons += "Several different causes can produce this message, so the suggestion cannot be certain." }
        if (truncated) { score -= 10; reasons += "The log was cut, so earlier errors may be missing." }
        if (role == ErrorRole.INFORMATIONAL && i.severity == Severity.ERROR) score = minOf(score, 30)
        score = score.coerceIn(5, 95)
        val sufficient = specific && score >= 45 && w.solution != null
        val level = when { score >= 75 -> Confidence.HIGH; score >= 50 -> Confidence.MEDIUM; else -> Confidence.LOW }
        return DiagnosticConfidence(level, score, reasons, sufficient)
    }

    private fun isSpecificRule(rule: String): Boolean =
        !(rule.endsWith(".other") || rule.endsWith(".generic") || rule == "log.error-line" || rule == "gradle.unrecognised" || rule == "gha.error-annotation" ||
            (rule == "jvm.exception"))

    private companion object {
        const val TRACE_WINDOW = 30
        const val NEAR_LINES = 6
        const val MAX_AFFECTED = 8
        const val MAX_TRACE_FILES = 4

        val MERGEABLE_RULES = setOf("kotlin.unresolved-reference", "java.cannot-find-symbol", "java.package-missing", "resource.not-found", "resource.attribute-not-found")
        val RESOLUTION_RULES = setOf("dependency.not-found", "dependency.failed-resolve", "dependency.plugin-missing")
        val LIBRARY_RULES = setOf("dependency.not-found", "dependency.failed-resolve")
        val UNRESOLVED_RULES = setOf("kotlin.unresolved-reference", "java.package-missing", "java.cannot-find-symbol")
        val RESOURCE_CATEGORIES = setOf(DiagnosisCategory.RESOURCE_LINKING, DiagnosisCategory.XML, DiagnosisCategory.MANIFEST)
        val CODE_CATEGORIES = setOf(DiagnosisCategory.KOTLIN_COMPILATION, DiagnosisCategory.JAVA_COMPILATION)
        val ENVIRONMENT_RULES = setOf("sdk.location", "sdk.licenses", "gradle.memory")
        val FUTURE_BREAKING_WARNINGS = setOf("gradle.deprecated-features", "gha.node-deprecated")
        val GENERIC_NAMES = setOf("core", "test", "util", "utils", "base", "common", "android", "runtime", "kotlin", "java", "api", "main", "app", "data", "ktx", "lib", "library")

        /** Rules whose message has several possible causes: their solution can never be rated as certain. */
        val CONFIDENCE_CAPS = mapOf(
            "kotlin.unresolved-reference" to 70, "java.cannot-find-symbol" to 70, "java.package-missing" to 75,
            "kotlin.type-mismatch" to 80, "java.incompatible-types" to 80, "kotlin.syntax" to 75, "java.syntax" to 75,
            "dependency.not-found" to 75, "dependency.network" to 70, "gha.exit-code" to 60, "gha.canceled" to 70,
            "gha.permission" to 70, "gha.git-auth" to 70, "gha.error-annotation" to 30, "gradle.unrecognised" to 20,
            "jvm.exception" to 55, "gradle.memory" to 75, "gha.runner-lost" to 70,
        )
    }
}
