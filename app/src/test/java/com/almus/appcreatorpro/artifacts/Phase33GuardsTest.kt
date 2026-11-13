package com.almus.appcreatorpro.artifacts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 33 safety guards on the SOURCE of the artifact manager. The manager may use only PUBLIC signing data (a
 * certificate fingerprint and a label). These checks fail if someone later wires it to the keystore, a password or
 * the signing secrets, or makes it log.
 */
class Phase33GuardsTest {
    private fun sources(vararg dirs: String): List<File> {
        val roots = listOf("src/main/java/com/almus/appcreatorpro", "app/src/main/java/com/almus/appcreatorpro")
        val base = roots.map { File(it) }.firstOrNull { it.isDirectory } ?: error("main sources not found from ${File(".").absolutePath}")
        return dirs.flatMap { d -> File(base, d).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }
    }

    private val forbidden = listOf(
        "SigningSecrets", "SigningStore", "SigningManager", "keystoreBase64", "keystoreBytes",
        "storePassword", "keyPassword", "SecretStore", ".secrets(", "KeystoreSecretStore",
        "Log.", "println(", "printStackTrace",
    )

    @Test
    fun the_artifact_engine_never_touches_keystores_passwords_or_logging() {
        val files = sources("service/artifacts")
        assertTrue("artifact sources must be found", files.size >= 10)
        for (f in files) {
            val text = f.readText()
            for (word in forbidden) {
                assertTrue("${f.name} must not contain '$word'", !text.contains(word))
            }
        }
    }

    @Test
    fun the_artifact_screens_never_touch_keystores_or_passwords() {
        val files = sources("ui/artifacts")
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            for (word in forbidden - listOf("SigningManager")) {
                assertTrue("${f.name} must not contain '$word'", !text.contains(word))
            }
        }
    }

    @Test
    fun saved_files_are_shared_only_from_the_apks_folder_never_the_checksum_records() {
        val provider = sources("..").let { File(it.first().path.substringBefore("/java/") + "/res/xml/file_paths.xml") }
        assertTrue(provider.isFile)
        val xml = provider.readText()
        assertTrue(xml.contains("path=\"apks/\""))
        assertTrue("checksum records must not be reachable through the FileProvider", !xml.contains("apk-meta"))
    }
}
