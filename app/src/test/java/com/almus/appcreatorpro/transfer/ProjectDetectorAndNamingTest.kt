package com.almus.appcreatorpro.transfer

import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.transfer.ProjectDetector
import com.almus.appcreatorpro.domain.transfer.ProjectListFilter
import com.almus.appcreatorpro.domain.transfer.ProjectNaming
import com.almus.appcreatorpro.domain.transfer.ProjectSort
import com.almus.appcreatorpro.domain.validation.ProjectNameValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ProjectDetectorAndNamingTest {
    private fun detect(vararg files: Pair<String, String>) =
        ProjectDetector.detect(files.map { it.first }) { path -> files.firstOrNull { it.first == path }?.second }

    @Test
    fun mixed_kotlin_and_java_is_kotlin() {
        val d = detect(
            "build.gradle" to "plugins { id 'com.android.application' }",
            "src/main/AndroidManifest.xml" to "<manifest package=\"com.a.b\"/>",
            "src/main/java/A.java" to "class A{}",
            "src/main/java/B.kt" to "class B",
        )
        assertEquals(ProjectType.ANDROID_KOTLIN, d.type)
        assertEquals("com.a.b", d.packageName)
    }

    @Test
    fun android_project_without_sources_is_a_problem() {
        val d = detect("src/main/AndroidManifest.xml" to "<manifest package=\"com.a.b\"/>")
        assertNull(d.type)
        assertTrue(d.problem!!.contains("no Kotlin or Java"))
    }

    @Test
    fun invalid_package_falls_back_with_a_warning() {
        val d = detect(
            "app/build.gradle" to "plugins { id 'com.android.application' }\nandroid { namespace 'Bad.Package' }",
            "app/src/main/java/A.java" to "class A{}",
        )
        assertEquals(ProjectDetector.FALLBACK_PACKAGE, d.packageName)
        assertTrue(d.warnings.any { it.contains("package name") })
    }

    @Test
    fun css_and_js_only_is_web_with_a_warning() {
        val d = detect("a.css" to "a{}", "b.js" to "1")
        assertEquals(ProjectType.WEB, d.type)
        assertTrue(d.warnings.any { it.contains("index.html") })
    }

    @Test
    fun web_project_with_package_json_takes_its_name() {
        val d = detect("index.html" to "<html/>", "package.json" to "{ \"name\": \"my-site\" }")
        assertEquals(ProjectType.WEB, d.type)
        assertEquals("my-site", d.nameHint)
        assertEquals("", d.packageName)
    }

    @Test
    fun sanitize_produces_valid_names() {
        for (raw in listOf("my-app (1)", "  ..hidden", "é", "", "x", "A very long project name that goes on and on forever and ever", "___", "日本語")) {
            val name = ProjectNaming.sanitize(raw)
            assertNull("'$raw' -> '$name'", ProjectNameValidator.validate(name).errorMessage)
        }
        assertEquals("my-app 1", ProjectNaming.sanitize("my-app (1)"))
    }

    @Test
    fun unique_respects_length_and_case() {
        assertEquals("Demo 2", ProjectNaming.unique("Demo", listOf("demo")))
        val long = "a".repeat(40)
        val u = ProjectNaming.unique(long, listOf(long))
        assertTrue(u.length <= 40)
        assertNull(ProjectNameValidator.validate(u, listOf(long)).errorMessage)
    }

    @Test
    fun export_file_name_is_safe() {
        val name = ProjectNaming.exportFileName("My App/../x", 0L, ZoneOffset.UTC)
        assertEquals("My-App-..-x-19700101-0000.zip".replace("..-", ""), name.replace("..-", ""))
        assertTrue(!name.contains("/") && name.endsWith(".zip"))
    }

    // ------------------------------------------------------------------ search / sort / recents

    private fun project(id: String, name: String, updated: Long, created: Long = 0L, type: ProjectType = ProjectType.WEB, pkg: String = "") =
        Project(id, name, pkg, "", created, updated, type)

    private val list = listOf(
        project("a", "Zebra", updated = 10, created = 3),
        project("b", "apple", updated = 30, created = 1, type = ProjectType.ANDROID_KOTLIN, pkg = "com.fruit.apple"),
        project("c", "Mango", updated = 20, created = 2, type = ProjectType.ANDROID_JAVA, pkg = "com.fruit.mango"),
    )

    @Test
    fun sort_orders() {
        fun ids(sort: ProjectSort, opened: Map<String, Long> = emptyMap()) = ProjectListFilter.apply(list, "", sort, opened).map { it.id }
        assertEquals(listOf("b", "c", "a"), ids(ProjectSort.RECENTLY_UPDATED))
        assertEquals(listOf("b", "c", "a"), ids(ProjectSort.NAME_AZ))
        assertEquals(listOf("a", "c", "b"), ids(ProjectSort.NAME_ZA))
        assertEquals(listOf("a", "c", "b"), ids(ProjectSort.NEWEST_CREATED))
        assertEquals(listOf("a", "c", "b"), ids(ProjectSort.RECENTLY_OPENED, mapOf("a" to 500L, "c" to 400L)))
        assertEquals(listOf("b", "c", "a"), ids(ProjectSort.RECENTLY_OPENED)) // nothing opened: falls back to updated
    }

    @Test
    fun search_matches_name_package_and_type_all_words() {
        fun ids(q: String) = ProjectListFilter.apply(list, q, ProjectSort.NAME_AZ).map { it.id }
        assertEquals(listOf("b"), ids("APP"))
        assertEquals(listOf("b", "c"), ids("fruit"))
        assertEquals(listOf("c"), ids("fruit java"))
        assertEquals(listOf("a"), ids("html"))
        assertEquals(emptyList<String>(), ids("nothing here"))
        assertEquals(3, ids("   ").size)
    }

    @Test
    fun recent_lists_only_opened_projects_newest_first_and_limited() {
        val opened = mapOf("a" to 1L, "b" to 3L, "c" to 2L, "gone" to 9L)
        assertEquals(listOf("b", "c", "a"), ProjectListFilter.recent(list, opened).map { it.id })
        assertEquals(listOf("b"), ProjectListFilter.recent(list, opened, limit = 1).map { it.id })
        assertTrue(ProjectListFilter.recent(list, emptyMap()).isEmpty())
    }
}
