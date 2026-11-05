package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTest {
    private val gradle = "plugins {\n    id(\"com.android.application\")\n}\ndependencies {\n    implementation(\"androidx.room:room-runtime:2.6.1\")\n    implementation(platform(\"androidx.compose:compose-bom:2024.10.01\"))\n    ksp(\"androidx.room:room-compiler:2.6.1\")\n    implementation(libs.core)\n}\n"
    private val project = mapOf(
        "settings.gradle.kts" to "include(\":app\")\n",
        "app/build.gradle.kts" to gradle,
        "app/src/main/java/com/x/LoginScreen.kt" to "package com.x\npassword = \"hunter2hunter2\"\nfun login() {}\n",
        "app/src/main/java/com/x/Main.kt" to "package com.x\nfun main() {}\n",
        "app/src/main/res/layout/login.xml" to "<LinearLayout/>",
        "app/release.jks" to "BINARY",
        "local.properties" to "sdk.dir=/x",
        "app/src/main/res/values/secrets.xml" to "<resources><string name=\"k\">AIzaSyA1234567890abcdefghijklmnopqrstuvw</string></resources>",
        "app/build/outputs/x.txt" to "output",
        "logo.png" to "PNG",
        ".git/config" to "git",
    )

    private suspend fun build(paths: List<String>, f: FakeProjectFiles = FakeProjectFiles(project), structure: Boolean = true) =
        ProjectContextBuilder(f).build("Notes", ProjectType.ANDROID_KOTLIN, paths, structure)

    @Test fun `only the requested files are included, secrets removed`() = runTest {
        val b = build(listOf("app/src/main/java/com/x/LoginScreen.kt"))
        assertEquals(listOf("app/src/main/java/com/x/LoginScreen.kt"), b.files.map { it.path })
        val text = b.render()
        assertFalse(text.contains("hunter2")); assertTrue(text.contains("fun login()")); assertEquals(1, b.redactionCount.coerceAtMost(1))
        assertFalse(text.contains("fun main()")) // an unrequested file is never included
        assertTrue(text.contains("PROJECT: Notes") && text.contains("Kotlin (Android)"))
    }

    @Test fun `sensitive, hidden, binary and missing files are skipped with a reason`() = runTest {
        val b = build(listOf("app/release.jks", "local.properties", "app/src/main/res/values/secrets.xml", "app/build/outputs/x.txt", "logo.png", ".git/config", "nope.kt"))
        assertTrue(b.files.isEmpty()); assertEquals(7, b.skipped.size)
        assertTrue(b.skipped.first { it.path == "nope.kt" }.reason.contains("not found"))
        assertTrue(b.skipped.first { it.path == "local.properties" }.reason.contains("secrets"))
    }

    @Test fun `structure lists paths without credential files or build output`() = runTest {
        val s = build(emptyList()).structure
        assertTrue("app/src/main/java/com/x/Main.kt" in s)
        for (hidden in listOf("app/release.jks", "local.properties", "app/build/outputs/x.txt", ".git/config", "app/src/main/res/values/secrets.xml")) assertFalse(hidden, hidden in s)
    }

    @Test fun `no structure when not wanted, and no file text in a structure-only bundle`() = runTest {
        val b = build(emptyList(), structure = false)
        assertTrue(b.structure.isEmpty()); assertFalse(b.render().contains("fun main"))
        assertFalse(build(emptyList()).render().contains("package com.x"))
    }

    @Test fun `dependencies come from gradle files as coordinates only`() = runTest {
        val b = build(emptyList())
        assertTrue("androidx.room:room-runtime:2.6.1" in b.dependencies); assertTrue("androidx.room:room-compiler:2.6.1" in b.dependencies)
        assertTrue("androidx.compose:compose-bom:2024.10.01" in b.dependencies); assertTrue("plugin com.android.application" in b.dependencies)
        assertFalse(b.render().contains("plugins {"))
    }

    @Test fun `limits on file count, file size and total size`() = runTest {
        val many = (1..20).associate { "src/F$it.kt" to "val a$it = 1\n" }
        assertEquals(ContextPolicy.MAX_FILES, build(many.keys.toList(), FakeProjectFiles(many)).files.size)
        assertEquals(8, build(many.keys.toList(), FakeProjectFiles(many)).skipped.size)

        val big = mapOf("Big.kt" to "x".repeat(ContextPolicy.MAX_FILE_CHARS + 500))
        val c = build(listOf("Big.kt"), FakeProjectFiles(big)).files.single()
        assertTrue(c.truncated); assertEquals(ContextPolicy.MAX_FILE_CHARS, c.content.length)

        val large = (1..6).associate { "L$it.kt" to "y".repeat(ContextPolicy.MAX_FILE_CHARS) }
        val b = build(large.keys.toList(), FakeProjectFiles(large))
        assertTrue(b.totalChars <= ContextPolicy.MAX_TOTAL_CHARS); assertTrue(b.skipped.any { it.reason.contains("too large") })
    }

    @Test fun `preview lists exactly what will be sent`() = runTest {
        val p = build(listOf("app/src/main/java/com/x/LoginScreen.kt", "app/src/main/java/com/x/Main.kt")).preview()
        assertEquals(2, p.size); assertTrue(p.first { it.path.endsWith("LoginScreen.kt") }.redactions > 0)
    }

    @Test fun `suggester finds files by name from the instruction`() {
        val paths = project.keys.toList()
        assertEquals("app/src/main/java/com/x/LoginScreen.kt", ContextSuggester.suggest("Change the login screen design to a modern blue gradient", paths).first())
        assertTrue(ContextSuggester.suggest("do something", paths).isEmpty())
        assertTrue(ContextSuggester.suggest("change the jks keystore", paths).none { it.endsWith(".jks") }) // never suggests credential files
    }
}
