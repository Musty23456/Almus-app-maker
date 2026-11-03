package com.almus.appcreatorpro.transfer

import com.almus.appcreatorpro.domain.error.ValidationException
import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileProjectStoreTransferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun staged(name: String): File {
        val dir = File(tmp.root, name)
        File(dir, "files/index.html").apply { parentFile.mkdirs() }.writeText("<html/>")
        File(dir, "extra/img/logo.png").apply { parentFile.mkdirs() }.writeBytes(PNG_BYTES)
        return dir
    }

    @Test
    fun import_staged_validates_name_and_package() = runTest {
        val env = TransferEnv(tmp)
        env.store.createProject("Existing", "com.example.existing", "")
        for ((name, pkg, type) in listOf(
            Triple("Existing", "", ProjectType.WEB),
            Triple("ab", "", ProjectType.WEB),
            Triple("Good Name", "Not A Package", ProjectType.ANDROID_KOTLIN),
        )) {
            try {
                env.store.importStaged(staged("s-$name-$pkg".replace(' ', '_')), name, pkg, "", type)
                fail("expected ValidationException for '$name' / '$pkg'")
            } catch (e: ValidationException) {
                // expected
            }
        }
        assertEquals(1, env.store.getProjects().size)
    }

    @Test
    fun staged_files_are_moved_into_the_project_and_survive_a_reload() = runTest {
        val env = TransferEnv(tmp)
        val dir = staged("s1")
        val project = env.store.importStaged(dir, "Web Site", "", "from test", ProjectType.WEB)
        assertFalse(File(dir, "files").exists())
        assertEquals(listOf("index.html"), env.store.listFiles(project.id).map { it.path })

        // a fresh store over the same folder sees the project and keeps the extra file
        val reopened = TransferEnv(tmp).store
        assertEquals(listOf("Web Site"), reopened.getProjects().map { it.name })
        assertTrue(File(env.projectsDir, "${project.id}/extra/img/logo.png").isFile)
    }

    @Test
    fun deleting_a_project_removes_its_extra_files_too() = runTest {
        val env = TransferEnv(tmp)
        val project = env.store.importStaged(staged("s2"), "Web Site", "", "", ProjectType.WEB)
        env.store.deleteProject(project.id)
        assertFalse(File(env.projectsDir, project.id).exists())
    }

    @Test
    fun project_with_only_extra_files_gets_an_empty_source_folder() = runTest {
        val env = TransferEnv(tmp)
        val dir = File(tmp.root, "only-extra")
        File(dir, "extra/a.bin").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(0, 1))
        val project = env.store.importStaged(dir, "Binary Only", "", "", ProjectType.WEB)
        assertTrue(env.store.listFiles(project.id).isEmpty())
        assertTrue(env.store.contentRoots(project.id).extraDir.resolve("a.bin").isFile)
    }

    @Test
    fun interrupted_replace_is_recovered_when_the_store_loads() = runTest {
        val env = TransferEnv(tmp)
        val project = env.store.importStaged(staged("s3"), "Web Site", "", "", ProjectType.WEB)
        // simulate a crash after the old files were put aside
        val dir = File(env.projectsDir, project.id)
        File(dir, "replaced-old").mkdirs()
        assertTrue(File(dir, "files").renameTo(File(dir, "replaced-old/files")))
        val reopened = TransferEnv(tmp).store
        assertEquals(listOf("index.html"), reopened.listFiles(project.id).map { it.path })
        assertFalse(File(dir, "replaced-old").exists())
    }
}
