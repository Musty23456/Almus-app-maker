package com.almus.appcreatorpro.repair

import com.almus.appcreatorpro.ai.AiChangeManager
import com.almus.appcreatorpro.ai.FakeProjectFiles
import com.almus.appcreatorpro.ai.InMemoryChangeHistoryStore
import com.almus.appcreatorpro.debug.CodeEdit
import com.almus.appcreatorpro.debug.Confidence
import com.almus.appcreatorpro.debug.DiagnosedIssue
import com.almus.appcreatorpro.debug.DiagnosisEngine
import com.almus.appcreatorpro.debug.FixOrigin
import com.almus.appcreatorpro.debug.FixProposal
import com.almus.appcreatorpro.debug.ProjectPathResolver
import com.almus.appcreatorpro.debug.TextEdits

const val MAIN = "app/src/main/java/com/example/app/MainActivity.kt"
const val UTIL = "app/src/main/java/com/example/app/Util.kt"

val MAIN_LINES = listOf(
    "package com.example.app", "", "import android.app.Activity", "", "class MainActivity : Activity() {",
    "    fun run() {", "        val a = 1", "        val b = 2", "        println(a + b)", "        println(\"start\")",
    "        println(\"middle\")", "        foo()", "        println(\"end\")", "    }", "}",
)
val MAIN_TEXT: String = MAIN_LINES.joinToString("\n") + "\n"
val FOO_LINE: Int = MAIN_LINES.indexOf("        foo()") + 1

val UTIL_TEXT: String = listOf("package com.example.app", "", "fun helper(): Int {", "    return undefinedThing", "}", "").joinToString("\n")

val PROJECT_PATHS = listOf(MAIN, UTIL, "app/build.gradle.kts", "settings.gradle.kts", "app/src/main/AndroidManifest.xml")

/** A real diagnosis of a Kotlin build log that names [MAIN] at [FOO_LINE]. */
fun diagnose(): DiagnosedIssue {
    val log = """
        > Task :app:compileDebugKotlin FAILED
        e: file:///home/runner/work/app/app/$MAIN:$FOO_LINE:9 Unresolved reference: foo
        FAILURE: Build failed with an exception.
        * What went wrong:
        Execution failed for task ':app:compileDebugKotlin'.
        > Compilation error. See log for more details
        BUILD FAILED in 12s
    """.trimIndent()
    return DiagnosisEngine(ProjectPathResolver(PROJECT_PATHS), PROJECT_PATHS).run(log, false).diagnosis.primary!!
}

fun proposal(
    issue: DiagnosedIssue?,
    id: String,
    path: String = MAIN,
    text: String = MAIN_TEXT,
    edit: CodeEdit = CodeEdit.replace(FOO_LINE, "        println(\"foo\")"),
    origin: FixOrigin = FixOrigin.RULE,
    title: String = "Fix $id",
) = FixProposal(id, issue?.id, title, "explanation $id", path, listOf(edit), origin, Confidence.MEDIUM, TextEdits.hash(text))

class Env(initial: Map<String, String> = mapOf(MAIN to MAIN_TEXT, UTIL to UTIL_TEXT), failWritesTo: Set<String> = emptySet()) {
    val files = FakeProjectFiles(initial, failWritesTo)
    val history = InMemoryChangeHistoryStore()
    val changes = AiChangeManager("p1", files, history, clockMillis = { 100L })
    val log = InMemoryRepairLogStore()
    val backups = InMemoryRepairBackupStore()
    var now = 1_000L
    fun session(): RepairSession = RepairSession("p1", files, changes, log, backups) { now++ }
}
