package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiChangeManager
import com.almus.appcreatorpro.ai.FakeAiBackend
import com.almus.appcreatorpro.ai.FakeProjectFiles
import com.almus.appcreatorpro.ai.GOOD_KOTLIN
import com.almus.appcreatorpro.ai.InMemoryChangeHistoryStore
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.service.github.MiniJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

const val FOO_PATH = "app/src/main/java/com/example/notes/Foo.kt"
const val BAR_PATH = "app/src/main/java/com/example/notes/Bar.kt"
const val MAIN_PATH = "app/src/main/java/com/example/notes/MainActivity.kt"
const val MAIN_TEXT = "package com.example.notes\n\nclass MainActivity {\n    fun title() = \"Notes\"\n}\n"
const val BAD_KOTLIN = "package com.example.notes\n\nclass Broken {\n    fun f() {\n        val x = (1 + 2\n    }\n}\n"
const val APP_GRADLE = "plugins {\n    id(\"com.android.application\")\n}\nandroid {\n    namespace = \"com.example.notes\"\n    compileSdk = 34\n    defaultConfig {\n        applicationId = \"com.example.notes\"\n    }\n}\ndependencies {\n    testImplementation(\"junit:junit:4.13.2\")\n}\n"
const val LAUNCHER_MANIFEST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application android:label=\"Notes\">\n        <activity android:name=\".MainActivity\" android:exported=\"true\">\n            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />\n            </intent-filter>\n        </activity>\n    </application>\n</manifest>\n"

/** A small, complete Android project: build files, manifest, one class. */
fun sampleProject(): Map<String, String> = mapOf(
    "settings.gradle.kts" to "rootProject.name = \"Notes\"\ninclude(\":app\")\n",
    "build.gradle.kts" to "plugins { id(\"com.android.application\") version \"8.5.0\" apply false }\n",
    "gradle.properties" to "android.useAndroidX=true\n",
    "app/build.gradle.kts" to APP_GRADLE,
    "app/src/main/AndroidManifest.xml" to LAUNCHER_MANIFEST,
    MAIN_PATH to MAIN_TEXT,
)

fun json(v: Any?): String = MiniJson.stringify(v)

fun taskSpec(id: String, title: String, kind: String = "code", paths: List<String> = emptyList(), dependsOn: List<String> = emptyList(), detail: String = "do $title"): Map<String, Any?> =
    mapOf("id" to id, "title" to title, "kind" to kind, "detail" to detail, "paths" to paths, "depends_on" to dependsOn)

fun planJson(vararg tasks: Map<String, Any?>, summary: String = "Build the notes feature", extra: Map<String, Any?> = emptyMap()): String =
    json(mapOf("understanding" to "You want notes.", "assumptions" to listOf("Offline only"), "summary" to summary, "architecture" to "MVVM", "tasks" to tasks.toList(), "risks" to emptyList<String>()) + extra)

fun createChange(path: String, content: String): Map<String, Any?> = mapOf("path" to path, "action" to "create", "explanation" to "new", "content" to content)
fun modifyChange(path: String, content: String): Map<String, Any?> = mapOf("path" to path, "action" to "modify", "explanation" to "changed", "content" to content)
fun editChange(path: String, find: String, replace: String): Map<String, Any?> =
    mapOf("path" to path, "action" to "modify", "explanation" to "edit", "edits" to listOf(mapOf("find" to find, "replace" to replace)))
fun deleteChange(path: String): Map<String, Any?> = mapOf("path" to path, "action" to "delete", "explanation" to "remove")

fun resultJson(vararg changes: Map<String, Any?>, summary: String = "Done it.", extra: Map<String, Any?> = emptyMap()): String =
    json(mapOf("summary" to summary, "changes" to changes.toList()) + extra)

fun toolCallsJson(vararg calls: Pair<String, Map<String, String>>): String =
    json(mapOf("tool_calls" to calls.map { (tool, args) -> mapOf("tool" to tool, "args" to args) }))

fun fooClass(name: String = "Foo"): String = GOOD_KOTLIN.replace("Foo", name)

class FakeProjects : ProjectRepository {
    val created = mutableListOf<Project>()
    var failWith: Exception? = null
    override fun observeProjects(): Flow<List<Project>> = MutableStateFlow(created.toList())
    override suspend fun getProjects() = created.toList()
    override suspend fun getProject(id: String) = created.firstOrNull { it.id == id }
    override suspend fun createProject(name: String, packageName: String, description: String, type: ProjectType): Project {
        failWith?.let { throw it }
        return Project("proj${created.size + 1}", name, packageName, description, 0L, 0L, type).also { created += it }
    }
    override suspend fun renameProject(id: String, newName: String): Project = error("unused")
    override suspend fun deleteProject(id: String) = error("unused")
}

/** Everything one agent test needs, around one [AgentRunner]. */
class AgentRig(
    scope: CoroutineScope,
    initial: Map<String, String> = sampleProject(),
    perms: AgentPermissions = AgentPermissions(),
    testRunner: AgentTestRunner = StaticTestRunner(),
    val store: AgentRunStore = InMemoryAgentRunStore(),
) {
    val files = FakeProjectFiles(initial)
    val backend = FakeAiBackend()
    val history = InMemoryChangeHistoryStore()
    val changes = AiChangeManager("p1", files, history) { 1_000L }
    private var ids = 0
    val runner = AgentRunner(
        "p1", "Notes", ProjectType.ANDROID_KOTLIN, backend, files, changes, store, scope, perms, testRunner,
        clockMillis = { 5_000L }, newId = { "run${++ids}" },
    )
    val state get() = runner.state.value
    fun task(id: String) = state.tasks.first { it.id == id }
}
