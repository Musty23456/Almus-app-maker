package com.almus.appcreatorpro.transfer

import com.almus.appcreatorpro.domain.transfer.IgnoreReason
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.ZipEntryGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ZipEntryGuardTest {
    private fun assertUnsafe(name: String) {
        try {
            ZipEntryGuard.normalize(name)
            fail("Expected '$name' to be rejected")
        } catch (e: TransferException) {
            assertEquals(TransferException.Kind.UNSAFE_PATH, e.kind)
        }
    }

    @Test
    fun normal_paths_are_cleaned() {
        assertEquals("app/src/Main.kt", ZipEntryGuard.normalize("app/src/Main.kt"))
        assertEquals("app/src/Main.kt", ZipEntryGuard.normalize("./app//src/./Main.kt"))
        assertEquals("a/b.txt", ZipEntryGuard.normalize("a\\b.txt"))
    }

    @Test
    fun root_only_names_return_null() {
        assertNull(ZipEntryGuard.normalize(""))
        assertNull(ZipEntryGuard.normalize("./"))
        assertNull(ZipEntryGuard.normalize("/".replace("/", "./")))
    }

    @Test
    fun traversal_is_rejected_in_every_form() {
        assertUnsafe("../evil.txt")
        assertUnsafe("a/../../evil.txt")
        assertUnsafe("a/b/../../../evil.txt")
        assertUnsafe("..\\evil.txt")
        assertUnsafe("a\\..\\..\\evil.txt")
        assertUnsafe("..")
    }

    @Test
    fun absolute_and_drive_paths_are_rejected() {
        assertUnsafe("/etc/passwd")
        assertUnsafe("\\windows\\system32")
        assertUnsafe("C:\\Windows\\evil.dll")
        assertUnsafe("c:/evil")
    }

    @Test
    fun control_characters_are_rejected() {
        assertUnsafe("a\u0000b.txt")
        assertUnsafe("a\nb.txt")
        assertUnsafe("a\tb")
    }

    @Test
    fun a_dotdot_inside_a_name_is_fine() {
        assertEquals("a/..b/c..d.txt", ZipEntryGuard.normalize("a/..b/c..d.txt"))
    }

    @Test
    fun ignore_rules() {
        assertEquals(IgnoreReason.VERSION_CONTROL, ZipEntryGuard.ignoreReason(".git/config"))
        assertEquals(IgnoreReason.VERSION_CONTROL, ZipEntryGuard.ignoreReason("app/.idea/workspace.xml"))
        assertEquals(IgnoreReason.BUILD_OUTPUT, ZipEntryGuard.ignoreReason(".gradle/8.9/x.bin"))
        assertEquals(IgnoreReason.BUILD_OUTPUT, ZipEntryGuard.ignoreReason("app/build/intermediates/x.class"))
        assertEquals(IgnoreReason.BUILD_OUTPUT, ZipEntryGuard.ignoreReason("build/outputs/apk/debug/app.txt"))
        assertEquals(IgnoreReason.DEPENDENCIES, ZipEntryGuard.ignoreReason("web/node_modules/x/index.js"))
        assertEquals(IgnoreReason.SYSTEM_JUNK, ZipEntryGuard.ignoreReason("__MACOSX/._a"))
        assertEquals(IgnoreReason.SYSTEM_JUNK, ZipEntryGuard.ignoreReason("img/.DS_Store"))
        assertEquals(IgnoreReason.SECRET, ZipEntryGuard.ignoreReason("local.properties"))
        assertEquals(IgnoreReason.SECRET, ZipEntryGuard.ignoreReason("keys/release.jks"))
        assertEquals(IgnoreReason.SECRET, ZipEntryGuard.ignoreReason("my.KEYSTORE"))
        assertEquals(IgnoreReason.COMPILED_PACKAGE, ZipEntryGuard.ignoreReason("out/app-debug.apk"))
    }

    @Test
    fun source_files_are_not_ignored() {
        assertNull(ZipEntryGuard.ignoreReason("app/src/main/java/Main.kt"))
        // a "build" folder of a web project is NOT generated Gradle output
        assertNull(ZipEntryGuard.ignoreReason("build/index.html"))
        assertNull(ZipEntryGuard.ignoreReason("src/build/Builder.kt"))
        assertNull(ZipEntryGuard.ignoreReason("gradle/wrapper/gradle-wrapper.jar"))
    }
}
