package com.almus.appcreatorpro.repair

import com.almus.appcreatorpro.ai.LineDiff
import com.almus.appcreatorpro.debug.SourceText
import com.almus.appcreatorpro.debug.StaticDiagnostics
import com.almus.appcreatorpro.debug.XmlChecker
import com.almus.appcreatorpro.service.github.MiniJson

/**
 * Checks the PATCHED text of one file before it is shown as acceptable.
 *
 * Honest scope: these are text checks (bracket balance, XML and JSON structure, leftover placeholders, package line,
 * how much was changed). They are not a compiler and cannot judge types or logic ([RepairText.NOT_A_COMPILER]).
 *
 * The important rule: the file may already be broken (that is often the very error being repaired), so problems are
 * compared with the ORIGINAL text. A problem that is NEW in the patched text blocks the patch. A problem that was
 * already there and is still there is only a warning ("the patch does not remove it").
 */
object PatchValidator {
    /** Changed lines (added + removed) above which the patch is called broad. */
    const val WARN_CHANGED_LINES = 40
    const val BLOCK_CHANGED_LINES = 150
    const val WARN_FRACTION = 0.4
    const val BLOCK_FRACTION = 0.8
    private const val MIN_LINES_FOR_FRACTION_WARN = 20
    private const val MIN_LINES_FOR_FRACTION_BLOCK = 30

    private val placeholder = Regex("(?m)^\\s*(?://|#|<!--)?\\s*\\.\\.\\.\\s*(?:-->)?\\s*$|rest of (?:the )?(?:file|code|class)|existing code here", RegexOption.IGNORE_CASE)
    private val packageLine = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)")

    fun validate(path: String, before: String?, after: String): PatchValidation {
        val problems = ArrayList<PatchProblem>()
        val checks = ArrayList<String>()
        val notes = ArrayList<String>()
        val lower = path.lowercase()

        // ---- basic sanity --------------------------------------------------------------------------------------
        checks += "The patched file is not empty and contains no binary data"
        if (after.isBlank()) problems += PatchProblem(ProblemSeverity.ERROR, "The patched file would be empty.")
        if (after.indexOf('\u0000') >= 0) problems += PatchProblem(ProblemSeverity.ERROR, "The patched text contains binary data.")

        // ---- leftovers of an unfinished answer -----------------------------------------------------------------
        checks += "No placeholder such as \"...\" or \"rest of the file\" was added"
        val placeholdersAfter = placeholder.findAll(after).count()
        val placeholdersBefore = if (before == null) 0 else placeholder.findAll(before).count()
        if (placeholdersAfter > placeholdersBefore) {
            problems += PatchProblem(ProblemSeverity.ERROR, "The patch adds a placeholder (\"...\" or \"rest of the file\"): it looks unfinished.")
        }

        // ---- syntax (only for the kinds of file that have a checker) -------------------------------------------
        val afterSyntax = syntaxProblems(lower, path, after)
        val checkerRan = afterSyntax != null
        if (afterSyntax == null) {
            notes += "No syntax checker exists for this kind of file, so only the general checks ran."
        } else {
            checks += afterSyntax.checkName
            val beforeSyntax = if (before == null) null else syntaxProblems(lower, path, before)
            val beforeCounts = HashMap<String, Int>()
            beforeSyntax?.problems?.forEach { p -> beforeCounts.merge(key(p), 1) { a, b -> a + b } }
            val seen = HashMap<String, Int>()
            for (p in afterSyntax.problems) {
                val k = key(p)
                val n = (seen[k] ?: 0) + 1
                seen[k] = n
                if (n <= (beforeCounts[k] ?: 0)) {
                    problems += PatchProblem(ProblemSeverity.WARNING, "Already there before the patch and still there: ${p.message}", p.line)
                } else {
                    problems += PatchProblem(ProblemSeverity.ERROR, "The patch introduces a syntax problem: ${p.message}", p.line)
                }
            }
            val stillThere = beforeCounts.entries.sumOf { e -> minOf(e.value, seen[e.key] ?: 0) }
            val removed = (beforeSyntax?.problems?.size ?: 0) - stillThere
            if (removed > 0) notes += "The patch removes $removed syntax problem(s) that were in the original file."
        }

        // ---- package line must survive (Kotlin / Java) ---------------------------------------------------------
        if (before != null && (lower.endsWith(".kt") || lower.endsWith(".java"))) {
            checks += "The package line is unchanged"
            val pb = packageLine.find(before)?.groupValues?.get(1)
            val pa = packageLine.find(after)?.groupValues?.get(1)
            if (pb != pa) problems += PatchProblem(ProblemSeverity.ERROR, "The package line was changed (\"${pb ?: "none"}\" to \"${pa ?: "none"}\"). A repair must not move a file to another package.")
        }

        // ---- minimal change ------------------------------------------------------------------------------------
        if (before != null && after != before) {
            checks += "The change is small (minimal repair)"
            val stats = LineDiff.stats(LineDiff.diff(before, after))
            val changed = stats.added + stats.removed
            val beforeLines = before.replace("\r\n", "\n").split('\n').size
            val fraction = if (beforeLines == 0) 0.0 else changed.toDouble() / beforeLines
            when {
                changed > BLOCK_CHANGED_LINES ->
                    problems += PatchProblem(ProblemSeverity.ERROR, "The patch changes $changed lines. That is a rewrite, not a minimal repair, and too much to review safely.")
                beforeLines >= MIN_LINES_FOR_FRACTION_BLOCK && fraction > BLOCK_FRACTION ->
                    problems += PatchProblem(ProblemSeverity.ERROR, "The patch changes most of the file ($changed of about $beforeLines lines). That is a rewrite, not a minimal repair.")
                changed > WARN_CHANGED_LINES ->
                    problems += PatchProblem(ProblemSeverity.WARNING, "The patch changes $changed lines. Check that every change is needed for this error.")
                beforeLines >= MIN_LINES_FOR_FRACTION_WARN && fraction > WARN_FRACTION ->
                    problems += PatchProblem(ProblemSeverity.WARNING, "The patch changes a large part of the file ($changed of about $beforeLines lines).")
            }
            if (stripWhitespace(before) == stripWhitespace(after)) {
                problems += PatchProblem(ProblemSeverity.WARNING, "Only whitespace changes. That cannot repair a build error.")
            }
        }

        val hasError = problems.any { it.severity == ProblemSeverity.ERROR }
        val hasWarning = problems.any { it.severity == ProblemSeverity.WARNING }
        val status = when {
            hasError -> ValidationStatus.BLOCKED
            hasWarning -> ValidationStatus.WARNINGS
            !checkerRan -> ValidationStatus.NOT_CHECKED
            else -> ValidationStatus.PASSED
        }
        return PatchValidation(status, problems, checks, notes)
    }

    private class Syntax(val checkName: String, val problems: List<SourceText.BracketProblem>)

    /** Null = this kind of file has no checker (or it is too big to check). Problems are (line, message) pairs. */
    private fun syntaxProblems(lowerPath: String, path: String, text: String): Syntax? {
        if (text.length > StaticDiagnostics.MAX_FILE_CHARS) return null
        return when {
            lowerPath.endsWith(".kt") || lowerPath.endsWith(".kts") ->
                Syntax("Brackets ( ) [ ] { } are balanced (strings and comments ignored)", SourceText.bracketProblems(SourceText.mask(text, kotlin = true), max = 5))
            lowerPath.endsWith(".java") || lowerPath.endsWith(".gradle") ->
                Syntax("Brackets ( ) [ ] { } are balanced (strings and comments ignored)", SourceText.bracketProblems(SourceText.mask(text, kotlin = false), max = 5))
            lowerPath.endsWith(".xml") -> {
                val found = try { XmlChecker.balance(path, text) } catch (e: Exception) { emptyList() }
                Syntax("The XML is well formed (tags closed and matched)", found.map { f -> SourceText.BracketProblem(f.issue.line ?: 1, f.issue.message) })
            }
            lowerPath.endsWith(".json") -> {
                val err = try { MiniJson.parse(text); null } catch (e: Exception) { e.message ?: "invalid JSON" }
                Syntax("The JSON can be parsed", if (err == null) emptyList() else listOf(SourceText.BracketProblem(1, "Not valid JSON: ${err.take(120)}")))
            }
            else -> null
        }
    }

    /** Line numbers move when lines are added, so problems are compared by their text with the digits removed. */
    private fun key(p: SourceText.BracketProblem): String = p.message.replace(Regex("\\d+"), "#")

    private fun stripWhitespace(s: String): String = s.filterNot { it.isWhitespace() }
}
