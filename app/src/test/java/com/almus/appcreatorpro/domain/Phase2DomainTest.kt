package com.almus.appcreatorpro.domain

import com.almus.appcreatorpro.domain.error.StorageException
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.template.ProjectTemplates
import com.almus.appcreatorpro.domain.validation.FilePathValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePathValidatorTest {
    @Test
    fun accepts_normal_paths() {
        for (ok in listOf("a.txt", "src/main/App.kt", ".gitignore", "my file.md", "a-b_c.1/d.txt")) {
            assertTrue(ok, FilePathValidator.validate(ok).isValid)
        }
    }

    @Test
    fun rejects_unsafe_or_malformed_paths() {
        val bad = listOf(
            "", "   ", "/abs.txt", "../up.txt", "a/../b.txt", "./x.txt", "a//b", "trailing/", " lead.txt",
            "trail.txt ", "we*ird.txt", "back\\slash.txt", "q?.txt", "emoji☃.txt", "a/b/c/d/e/f/g/h/i/j/k/l/m.txt",
            "x".repeat(81), "p/" + "y".repeat(200), "file.almus-tmp",
        )
        for (path in bad) assertFalse("'$path' should be invalid", FilePathValidator.validate(path).isValid)
    }

    @Test
    fun errors_have_messages() {
        assertEquals("File name is required.", FilePathValidator.validate("").errorMessage)
        assertNull(FilePathValidator.validate("ok.txt").errorMessage)
    }
}

class ProjectTemplatesTest {
    @Test
    fun every_type_has_its_default_file_and_non_empty_content() {
        for (type in ProjectType.entries) {
            val pkg = if (type.requiresPackage) "com.example.demo" else ""
            val files = ProjectTemplates.filesFor("Demo", pkg, type)
            assertTrue(ProjectTemplates.defaultOpenFile(type, pkg) in files.keys)
            assertTrue(files.values.all { it.isNotBlank() })
            assertTrue(files.keys.all { FilePathValidator.validate(it).isValid })
        }
    }

    @Test
    fun android_templates_contain_package_and_name() {
        val kotlin = ProjectTemplates.filesFor("Demo App", "com.example.demo", ProjectType.ANDROID_KOTLIN)
        assertTrue(kotlin.getValue("app/src/main/java/com/example/demo/MainActivity.kt").startsWith("package com.example.demo"))
        assertTrue(kotlin.getValue("app/build.gradle.kts").contains("applicationId = \"com.example.demo\""))
        assertTrue(kotlin.getValue("settings.gradle.kts").contains("rootProject.name = \"Demo App\""))

        val java = ProjectTemplates.filesFor("Demo App", "com.example.demo", ProjectType.ANDROID_JAVA)
        assertTrue(java.getValue("app/src/main/java/com/example/demo/MainActivity.java").contains("public class MainActivity"))
        assertFalse(java.getValue("app/build.gradle.kts").contains("kotlin"))
    }

    @Test
    fun web_template_links_its_files_and_escapes_the_name() {
        val web = ProjectTemplates.filesFor("A&B <site>", "", ProjectType.WEB)
        assertEquals(setOf("README.md", "index.html", "style.css", "script.js"), web.keys)
        val html = web.getValue("index.html")
        assertTrue(html.contains("style.css") && html.contains("script.js"))
        assertTrue(html.contains("A&amp;B &lt;site&gt;"))
        assertFalse(html.contains("<site>"))
    }
}

class StorageExceptionTest {
    @Test
    fun user_message_is_the_exception_message() {
        val e = StorageException(StorageException.Kind.IO, "Disk is full.")
        assertEquals("Disk is full.", e.toUserMessage())
    }

    @Test
    fun project_type_ids_round_trip_and_unknown_is_null() {
        for (t in ProjectType.entries) assertEquals(t, ProjectType.fromId(t.id))
        assertNull(ProjectType.fromId("martian"))
        assertNull(ProjectType.fromId(null))
    }
}
