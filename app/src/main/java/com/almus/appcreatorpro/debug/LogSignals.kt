package com.almus.appcreatorpro.debug

/** What the diagnosis layer knows about a problem that [LogSignals] found (the analyzer's rules are covered by [ErrorExplanations]). */
internal class SignalInfo(
    val category: DiagnosisCategory,
    val scope: FixScope,
    val why: String,
)

/**
 * Phase 22: log patterns that [BuildErrorAnalyzer] does not cover, because they are not compiler or Gradle messages:
 *
 *  - GitHub Actions workflow and runner problems (an action that cannot be resolved, token permissions, a full disk,
 *    a lost runner, a time limit, git authentication, a workflow file with a syntax error),
 *  - network failures that the analyzer's single download rule does not distinguish (TLS, unreachable, truncated),
 *  - dependency and packaging conflicts (version constraints, classpath conflicts, duplicate files in the APK),
 *  - keystore / signing messages.
 *
 * Each rule matches ONE well-known message, never a vague word, and says what the message means in general. The result
 * is a list of ordinary [BuildIssue]s so the rest of the app (dashboard, navigation, AI request) needs no changes.
 *
 * A line that ends in a CI error marker (`##[error]`) is reported as an error, `##[warning]` as a warning, whatever the
 * rule's default is: the runner's own classification of the line wins over ours.
 */
internal object LogSignals {

    private class Rule(
        val id: String,
        val pattern: Regex,
        val category: ErrorCategory,
        val diagnosis: DiagnosisCategory,
        val severity: Severity,
        val scope: FixScope,
        /** Group (1-based) that holds the name the message is about, or 0. */
        val symbolGroup: Int = 0,
        /** Groups of file and line for messages that carry a location (a workflow file with Line: N), or 0. */
        val fileGroup: Int = 0,
        val lineGroup: Int = 0,
        /** Group whose text is the issue's message (default: the whole line). */
        val messageGroup: Int = 0,
        /** What the message means in general (text used as "Technical explanation"); receives the matched line. */
        val why: (MatchResult, String) -> String,
        val suggest: (MatchResult, String) -> String,
    )

    private val rules: List<Rule> = listOf(
        // ---- GitHub Actions -------------------------------------------------------------------------------------
        Rule(
            "gha.workflow-syntax", Regex("(\\.github/workflows/[\\w.\\-]+\\.ya?ml) \\(Line: (\\d+), Col: (\\d+)\\): (.+)"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.PROJECT_CONFIG, fileGroup = 1, lineGroup = 2, messageGroup = 4,
            why = { m, _ -> "GitHub validates the workflow file before it runs anything. It rejected this file at line ${m.groupValues[2]}, column ${m.groupValues[3]}: ${m.groupValues[4].trim().take(200)}." },
            suggest = { m, _ -> "Open `${m.groupValues[1]}` at line ${m.groupValues[2]} and fix what the message names (a misspelled key, a wrong value type, or wrong indentation are the usual reasons)." },
        ),
        Rule(
            "gha.workflow-invalid", Regex("Invalid workflow file:? ?(\\.github/workflows/[\\w.\\-]+\\.ya?ml)#L(\\d+)"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.PROJECT_CONFIG, fileGroup = 1, lineGroup = 2,
            why = { m, _ -> "GitHub could not read the workflow file `${m.groupValues[1]}`; it points at line ${m.groupValues[2]}. The log line itself does not say what is wrong there." },
            suggest = { m, _ -> "Open `${m.groupValues[1]}` at line ${m.groupValues[2]}. Check indentation (YAML uses spaces, not tabs), that keys are spelled correctly and that values have the right type." },
        ),
        Rule(
            "gha.action-not-found", Regex("Unable to resolve action [`'\"]?([^`'\",\\s]+)"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.PROJECT_CONFIG, symbolGroup = 1,
            why = { m, _ -> "A `uses:` line of the workflow names `${m.groupValues[1]}`, and GitHub could not find it. Possible reasons: a misspelled owner or repository, a tag or branch that does not exist, or a private repository this workflow may not use." },
            suggest = { m, _ -> "Open the workflow file and check the `uses:` line for `${m.groupValues[1]}`: the owner/repo spelling, and that the version after @ exists (open the action's page on GitHub and compare its releases)." },
        ),
        Rule(
            "gha.permission", Regex("Resource not accessible by integration"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.PROJECT_CONFIG,
            why = { _, _ -> "The automatic token of the run (GITHUB_TOKEN) does not have the permission this step needs. The message does not say which permission; defaults can be read-only, depending on the repository's settings." },
            suggest = { _, _ -> "Add a `permissions:` block to the workflow or job with the scope the step needs (for example `contents: write` to push or create a release, `pull-requests: write` to comment). Also check Settings > Actions > General > Workflow permissions. Name the failing step in the log to pick the right scope." },
        ),
        Rule(
            "gha.disk-full", Regex("No space left on device|\\bENOSPC\\b"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.ENVIRONMENT,
            why = { _, _ -> "The disk of the build machine is full. Hosted runners have a limited amount of free disk space, which large caches, build outputs or downloads can use up." },
            suggest = { _, _ -> "Remove what the build does not need before the failing step (old build outputs, large caches, unused preinstalled tools), reduce what is cached, or use a runner with more disk space." },
        ),
        Rule(
            "gha.runner-lost", Regex("The runner has received a shutdown signal|runner .*lost communication|hosted runner lost communication"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.ENVIRONMENT,
            why = { _, _ -> "The machine that ran the job stopped or lost contact with GitHub, so the job could not finish. That is a problem of the runner, not a result of your build." },
            suggest = { _, _ -> "Re-run the job. If it happens every time, the build may use more memory or CPU than the runner has; then reduce parallel work or use a larger runner." },
        ),
        Rule(
            "gha.timeout", Regex("exceeded the maximum execution time of (\\d+) minutes"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.PROJECT_CONFIG, symbolGroup = 1,
            why = { m, _ -> "The job ran for longer than its limit of ${m.groupValues[1]} minutes and was stopped. The log does not say which step was slow." },
            suggest = { _, _ -> "Look at the timestamps of the steps to find the slow one (often a download or a hanging process), enable caching for Gradle, or raise `timeout-minutes` on the job if the work is legitimate." },
        ),
        Rule(
            "gha.exit-code", Regex("Process completed with exit code (126|127|137|139|143)\\b"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.UNKNOWN, symbolGroup = 1,
            why = { m, _ ->
                when (m.groupValues[1]) {
                    "126" -> "Exit code 126 means a command was found but could not be executed, typically because the file is not executable."
                    "127" -> "Exit code 127 means the shell could not find the command that the step runs."
                    "137" -> "Exit code 137 means the process was killed with SIGKILL. On CI the most common reason is that the operating system ran out of memory, but the exit code alone does not prove that."
                    "139" -> "Exit code 139 means the process crashed with a segmentation fault (SIGSEGV), which points to a crash inside a native program rather than a build-script error."
                    else -> "Exit code 143 means the process received SIGTERM: it was asked to stop, for example because the job was cancelled or timed out."
                }
            },
            suggest = { m, _ ->
                when (m.groupValues[1]) {
                    "126" -> "Make the script executable (`chmod +x ./gradlew` in a step before it, or commit it with the executable bit)."
                    "127" -> "Check the command name and that the tool is installed on the runner (or add the step that installs it). Look at the line just above for the command that failed."
                    "137" -> "Check the log for memory errors before this line. If memory is the cause, lower `org.gradle.jvmargs` / the number of parallel workers, or use a runner with more memory."
                    "139" -> "Check the lines just before for the program that crashed and try a different version of it."
                    else -> "Check whether the run was cancelled by someone, by a `concurrency:` setting, or by a timeout, and re-run it."
                }
            },
        ),
        Rule(
            "gha.canceled", Regex("Canceling since a higher priority waiting request|The operation was canceled\\.|The job was canceled"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.ENVIRONMENT,
            why = { _, _ -> "The run was cancelled (by a person, by the workflow's `concurrency:` setting because a newer run started, or by a timeout). The build itself did not report a failure in this log." },
            suggest = { _, _ -> "If this was not intended, check `concurrency:` / `cancel-in-progress` in the workflow and re-run the job." },
        ),
        Rule(
            "gha.artifact-missing", Regex("No files were found with the provided path: (.+?)\\. No artifacts will be uploaded"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.WARNING, FixScope.PROJECT_CONFIG, symbolGroup = 1,
            why = { m, _ -> "The upload step looked for `${m.groupValues[1].take(120)}` and found no file. Either the build step before it did not create the file (for example because it failed), or the path or pattern is wrong." },
            suggest = { _, _ -> "Make sure the build step succeeded, then compare the path with where Gradle writes the output (for a default debug build: app/build/outputs/apk/debug/)." },
        ),
        Rule(
            "gha.git-auth", Regex("could not read Username|Authentication failed for|remote: Permission to .+ denied|Permission denied \\(publickey\\)|Support for password authentication was removed|remote: Repository not found|fatal: repository '.+' not found"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.PROJECT_CONFIG,
            why = { _, _ -> "Git was refused access to a repository. Possible reasons: the token or key used is missing, expired or has too few permissions (pushing needs write access), or the repository does not exist or is private for this token." },
            suggest = { _, _ -> "Check the repository URL, and that the token or key used by this step is set (in repository secrets) and has access to that repository. For pushes, the token needs write permission." },
        ),
        Rule(
            "gha.missing-input", Regex("Input required and not supplied: ([\\w\\-]+)"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.PROJECT_CONFIG, symbolGroup = 1,
            why = { m, _ -> "The action needs an input named `${m.groupValues[1]}` and the workflow step does not provide it (or provides an empty value, for example from a secret that is not set)." },
            suggest = { m, _ -> "Add `${m.groupValues[1]}:` under `with:` of that step. If the value comes from a secret, check the secret exists in the repository settings." },
        ),
        Rule(
            "gha.setup-version", Regex("Could not find satisfied version for SemVer '([^']+)'|The version '([^']+)' with architecture '([^']+)' was not found"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.ERROR, FixScope.PROJECT_CONFIG,
            why = { _, line -> "A setup step asked for a tool version that is not available on this runner (${line.trim().take(160)})." },
            suggest = { _, _ -> "Check the version (and distribution, if the action has that input) in the `with:` block of the setup step against the versions the action supports." },
        ),
        Rule(
            "gha.node-deprecated", Regex("Node\\.js (\\d+) actions are deprecated"),
            ErrorCategory.GITHUB_ACTIONS, DiagnosisCategory.GITHUB_ACTIONS, Severity.WARNING, FixScope.PROJECT_CONFIG, symbolGroup = 1,
            why = { m, _ -> "An action in the workflow is built for Node.js ${m.groupValues[1]}, which GitHub is phasing out. It still runs now; later it will be run with a newer Node.js or be refused." },
            suggest = { _, _ -> "Update the action named in the warning to its latest major version (change the version after @ in the `uses:` line)." },
        ),

        // ---- Network (kinds the analyzer's single download rule does not recognise) ------------------------------
        Rule(
            "network.tls", Regex("PKIX path building failed|unable to find valid certification path|SSLPeerUnverifiedException"),
            ErrorCategory.NETWORK, DiagnosisCategory.NETWORK, Severity.ERROR, FixScope.ENVIRONMENT,
            why = { _, line -> NetworkInterpreter.interpret(line)?.why ?: "A secure connection could not be verified." },
            suggest = { _, line -> NetworkInterpreter.interpret(line)?.solution ?: "Check the date and time of the build machine and that the JDK trusts the server's certificate." },
        ),
        Rule(
            "network.unreachable", Regex("Network is unreachable|No route to host"),
            ErrorCategory.NETWORK, DiagnosisCategory.NETWORK, Severity.ERROR, FixScope.ENVIRONMENT,
            why = { _, line -> NetworkInterpreter.interpret(line)?.why ?: "The network is unreachable from the build machine." },
            suggest = { _, line -> NetworkInterpreter.interpret(line)?.solution ?: "Check that the build machine has internet access, then run the build again." },
        ),
        Rule(
            "network.interrupted", Regex("Premature end of Content-Length|Unexpected end of file from server|Software caused connection abort"),
            ErrorCategory.NETWORK, DiagnosisCategory.NETWORK, Severity.ERROR, FixScope.ENVIRONMENT,
            why = { _, line -> NetworkInterpreter.interpret(line)?.why ?: "A download was cut off before it finished." },
            suggest = { _, line -> NetworkInterpreter.interpret(line)?.solution ?: "Run the build again." },
        ),
        Rule(
            "network.gradle-distribution", Regex("Could not (?:install|download) Gradle distribution"),
            ErrorCategory.NETWORK, DiagnosisCategory.NETWORK, Severity.ERROR, FixScope.ENVIRONMENT,
            why = { _, _ -> "The Gradle wrapper could not download the Gradle version named in gradle/wrapper/gradle-wrapper.properties. The usual reasons are a network problem on the build machine or a `distributionUrl` that is wrong." },
            suggest = { _, _ -> "Run the build again. If it repeats, open `distributionUrl` in gradle/wrapper/gradle-wrapper.properties in a browser to confirm the file exists." },
        ),

        // ---- Dependency and packaging conflicts ----------------------------------------------------------------
        Rule(
            "conflict.version-constraints", Regex("Cannot find a version of '(.+?)' that satisfies the version constraints"),
            ErrorCategory.MISSING_DEPENDENCY, DiagnosisCategory.DEPENDENCY_CONFLICT, Severity.ERROR, FixScope.PROJECT_CONFIG, symbolGroup = 1,
            why = { m, _ -> "Several places in the build ask for `${m.groupValues[1]}` with version requirements that cannot all be true at once (for example one asks for exactly 1.2 while another needs 1.5 or newer). The lines below the message in the log list who asks for what." },
            suggest = { m, _ -> "Read the constraint lines under this message in the log, then change the version that is too strict, or choose a version inside every range. `./gradlew :app:dependencyInsight --dependency ${m.groupValues[1].substringAfter(':')}` shows who requires what." },
        ),
        Rule(
            "conflict.classpath", Regex("Conflict with dependency '(.+?)' in project '(.+?)'\\. Resolved versions for (\\w+) \\((.+?)\\) and (\\w+) \\((.+?)\\) differ"),
            ErrorCategory.MISSING_DEPENDENCY, DiagnosisCategory.DEPENDENCY_CONFLICT, Severity.ERROR, FixScope.PROJECT_CONFIG, symbolGroup = 1,
            why = { m, _ -> "The ${m.groupValues[3]} classpath resolves `${m.groupValues[1]}` to ${m.groupValues[4]}, but the ${m.groupValues[5]} classpath resolves it to ${m.groupValues[6]}. Gradle reports this because the app could then compile against one version and run on another." },
            suggest = { m, _ -> "Make both classpaths use the same version of `${m.groupValues[1]}`: declare it with one explicit version (a `constraints { }` block or the library's BOM) so every configuration resolves the same one." },
        ),
        Rule(
            "conflict.packaging", Regex("More than one file was found with OS independent path '(.+?)'|\\d+ files found with path '(.+?)'"),
            ErrorCategory.MISSING_DEPENDENCY, DiagnosisCategory.DEPENDENCY_CONFLICT, Severity.ERROR, FixScope.PROJECT_CONFIG,
            why = { _, line -> "Two or more libraries contain a file with the same path inside the APK (${line.trim().take(160)}). This is typical for licence and metadata files under META-INF. The packaging step cannot pick one automatically." },
            suggest = { m, _ ->
                val path = m.groupValues[1].ifEmpty { m.groupValues[2] }
                "In `android { }` of app/build.gradle(.kts) add `packaging { resources { pickFirsts += \"$path\" } }` (keep one copy) or `excludes += \"$path\"` (keep none). Excluding is fine for licence/metadata files, but not for files your app reads at run time."
            },
        ),
        Rule(
            "conflict.agp-kotlin", Regex("The Android Gradle plugin supports only Kotlin Gradle plugin version ([\\d.]+) and higher"),
            ErrorCategory.MISSING_DEPENDENCY, DiagnosisCategory.DEPENDENCY_CONFLICT, Severity.ERROR, FixScope.PROJECT_CONFIG, symbolGroup = 1,
            why = { m, _ -> "The Android Gradle plugin and the Kotlin Gradle plugin must be compatible, and the Kotlin plugin in this project is older than ${m.groupValues[1]}, the minimum this AGP version supports." },
            suggest = { m, _ -> "Raise the Kotlin plugin version (the `org.jetbrains.kotlin.android` plugin) to ${m.groupValues[1]} or newer, or use an older Android Gradle plugin that still supports your Kotlin version." },
        ),

        // ---- Signing ---------------------------------------------------------------------------------------------
        Rule(
            "signing.keystore", Regex("Keystore was tampered with, or password was incorrect|Failed to read key .+ from store|Cannot recover key|Keystore file '.+' not found"),
            ErrorCategory.GRADLE, DiagnosisCategory.GRADLE, Severity.ERROR, FixScope.PROJECT_CONFIG,
            why = { _, _ -> "Signing could not open the keystore or read the key from it. Possible reasons: the store password or key password is wrong, the key alias does not exist in that keystore, or the keystore file is missing on this machine." },
            suggest = { _, _ -> "Check the store password, key alias and key password against the keystore, and in CI that the secrets are set and that the keystore file is written to the path the build expects. Do not put these values in the build file in plain text." },
        ),
    )

    class Result(val issues: List<BuildIssue>, val replacedIssueIds: Set<String>, val infoById: Map<String, SignalInfo>)

    private val TASK_HEADER = Regex("^> Task (:\\S+?)(?: FAILED)?$")
    private const val MAX_LINE = 4000
    private const val MAX_PER_RULE = 5
    private const val MAX_TOTAL = 40

    /**
     * Scans [lines] (the same cleaned lines the analyzer read, so indexes agree) and returns what it found.
     * An analyzer issue at the same log line is not duplicated: a network signal is skipped when the analyzer already
     * reported that line; a signal replaces an analyzer issue on its line only when that issue is a bare summary
     * ("Process completed with exit code N") or the generic `log.error-line`.
     */
    fun scan(lines: List<LogCleaner.Line>, resolver: ProjectPathResolver?, existing: List<BuildIssue>): Result {
        val byIndex = existing.filter { it.logIndex != null }.groupBy { it.logIndex!! }
        val out = ArrayList<BuildIssue>()
        val replaced = HashSet<String>()
        val info = HashMap<String, SignalInfo>()
        val perRule = HashMap<String, Int>()
        val seen = HashSet<String>()
        var task: String? = null
        for ((i, line) in lines.withIndex()) {
            if (out.size >= MAX_TOTAL) break
            val trimmed = line.text.trim()
            if (trimmed.isEmpty()) continue
            TASK_HEADER.matchEntire(trimmed)?.let { task = it.groupValues[1] }
            val text = if (trimmed.length > MAX_LINE) trimmed.substring(0, MAX_LINE) else trimmed
            for (r in rules) {
                val m = r.pattern.find(text) ?: continue
                val here = byIndex[i].orEmpty()
                val blocking = here.filter { !(it.rule.startsWith("summary.") || it.rule == "log.error-line") }
                if (blocking.isNotEmpty()) break
                if (perRule.getOrDefault(r.id, 0) >= MAX_PER_RULE) break
                val file = if (r.fileGroup > 0) m.groupValues[r.fileGroup] else null
                val ln = if (r.lineGroup > 0) m.groupValues[r.lineGroup].toIntOrNull() else null
                val message = LogCleaner.redact(if (r.messageGroup > 0) m.groupValues[r.messageGroup].ifBlank { text } else text).take(400)
                val severity = when (line.marker) {
                    LogCleaner.Marker.ERROR -> Severity.ERROR
                    LogCleaner.Marker.WARNING -> Severity.WARNING
                    else -> r.severity
                }
                val id = Fingerprint.of(r.id, file ?: "", ln, message)
                if (!seen.add(id)) break
                val symbol = if (r.symbolGroup > 0) m.groupValues.getOrNull(r.symbolGroup)?.trim()?.ifEmpty { null } else null
                out += BuildIssue(
                    id = id, rule = r.id, category = r.category, severity = severity, message = message,
                    file = file, projectPath = resolver?.resolve(file), line = ln, column = null,
                    suggestion = r.suggest(m, text), source = IssueSource.BUILD_LOG, confidence = Confidence.HIGH,
                    task = task, symbol = symbol,
                    context = (i until minOf(lines.size, i + 3)).map { LogCleaner.redact(lines[it].text.take(MAX_LINE)) },
                    logIndex = i,
                )
                info[id] = SignalInfo(r.diagnosis, r.scope, r.why(m, text))
                perRule[r.id] = perRule.getOrDefault(r.id, 0) + 1
                here.filter { it.rule.startsWith("summary.") || it.rule == "log.error-line" }.forEach { replaced += it.id }
                break
            }
        }
        return Result(out, replaced, info)
    }

    /**
     * Lines the CI runner itself marked as errors (`##[error]`) that nothing else explained: reported once each, with an
     * honest "the log does not say why" text, so a failure is never silently dropped.
     */
    fun unexplainedErrorMarkers(lines: List<LogCleaner.Line>, known: List<BuildIssue>): Result {
        val knownIndexes = known.mapNotNull { it.logIndex }.toHashSet()
        val knownText = known.flatMap { it.context + it.message }.toHashSet()
        val out = ArrayList<BuildIssue>()
        val info = HashMap<String, SignalInfo>()
        for ((i, line) in lines.withIndex()) {
            if (out.size >= 5) break
            if (line.marker != LogCleaner.Marker.ERROR) continue
            val text = line.text.trim()
            if (text.isEmpty() || i in knownIndexes) continue
            if (text.startsWith("Process completed with exit code")) continue
            val redacted = LogCleaner.redact(text.take(MAX_LINE)).take(400)
            if (redacted in knownText || known.any { k -> k.message.contains(redacted.take(60)) }) continue
            val id = Fingerprint.of("gha.error-annotation", i, redacted)
            info[id] = SignalInfo(DiagnosisCategory.GITHUB_ACTIONS, FixScope.UNKNOWN, "The CI runner marked this line as an error, and nothing in the log explains it further. The log alone does not show what caused it.")
            out += BuildIssue(
                id = id, rule = "gha.error-annotation", category = ErrorCategory.GITHUB_ACTIONS,
                severity = Severity.ERROR, message = redacted, file = null, projectPath = null, line = null, column = null,
                suggestion = null, source = IssueSource.BUILD_LOG, confidence = Confidence.MEDIUM, logIndex = i,
                context = (i until minOf(lines.size, i + 2)).map { LogCleaner.redact(lines[it].text.take(MAX_LINE)) },
            )
        }
        return Result(out, emptySet(), info)
    }
}
